package com.nhatnguyen.flashsale;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.modules.order.dto.CreateOrderRequest;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.producer.OrderEventProducer;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.order.service.OrderService;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@SpringBootTest
@ActiveProfiles("test")
class FlashSaleConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private OrderEventProducer orderEventProducer;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        lenient().when(valueOperations.get(any())).thenReturn(null);

        orderRepository.deleteAll();
        ticketRepository.deleteAll();
    }

    @Test
    @DisplayName("Pessimistic Lock: 100 concurrent threads buying 10 tickets must result in exactly 10 successes and 0 overselling")
    void testPessimisticLockZeroOverselling() throws InterruptedException {
        // Arrange: 10 tickets in stock
        Ticket ticket = new Ticket(
                null,
                "Blackpink World Tour - Limited VIP",
                "BP-VIP-10",
                new BigDecimal("150.00"),
                10,
                10
        );
        Ticket savedTicket = ticketRepository.save(ticket);
        Long ticketId = savedTicket.getId();

        int totalThreads = 100;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Act: 100 threads fire simultaneously
        for (int i = 0; i < totalThreads; i++) {
            final String userId = "user-" + i;
            executor.submit(() -> {
                try {
                    startGate.await(); // All threads synchronize here
                    CreateOrderRequest request = new CreateOrderRequest(ticketId, userId, 1, "PESSIMISTIC");
                    OrderResponse response = orderService.createOrder(request, UUID.randomUUID().toString());
                    if (response != null) {
                        successCount.incrementAndGet();
                    }
                } catch (OutOfStockException oos) {
                    outOfStockCount.incrementAndGet();
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown(); // Unleash the thunder
        endGate.await();
        executor.close();

        // Assert: Invariant Primacy Check
        Ticket finalTicket = ticketRepository.findById(ticketId).orElseThrow();
        long totalOrdersInDb = orderRepository.countByTicketId(ticketId);

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(outOfStockCount.get()).isEqualTo(90);
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(finalTicket.getStock()).isEqualTo(0);
        assertThat(totalOrdersInDb).isEqualTo(10);
    }

    @Test
    @DisplayName("Optimistic Lock: Ensures no negative stock under concurrent contention")
    void testOptimisticLockStockIntegrity() throws InterruptedException {
        // Arrange: 5 tickets in stock
        Ticket ticket = new Ticket(
                null,
                "Maroon 5 Concert - Standing",
                "M5-STAND-5",
                new BigDecimal("80.00"),
                5,
                5
        );
        Ticket savedTicket = ticketRepository.save(ticket);
        Long ticketId = savedTicket.getId();

        int totalThreads = 20;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            final String userId = "user-opt-" + i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    CreateOrderRequest request = new CreateOrderRequest(ticketId, userId, 1, "OPTIMISTIC");
                    orderService.createOrder(request, UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                    // Optimistic lock conflict or out-of-stock expected
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();
        executor.close();

        // Assert: Invariant: Final stock >= 0 and never negative, and @Version actually incremented!
        Ticket finalTicket = ticketRepository.findById(ticketId).orElseThrow();
        long totalOrdersInDb = orderRepository.countByTicketId(ticketId);

        assertThat(finalTicket.getStock()).isGreaterThanOrEqualTo(0);
        assertThat(finalTicket.getStock() + totalOrdersInDb).isEqualTo(5);
        assertThat(successCount.get()).isEqualTo((int) totalOrdersInDb);
        assertThat(finalTicket.getVersion()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Idempotency Guard: Duplicate X-Idempotency-Key must not double-deduct stock")
    void testDuplicateIdempotencyKeyDoesNotDoubleDeductStock() {
        Ticket ticket = new Ticket(
                null,
                "Ha Anh Tuan - Live Concert",
                "HAT-IDEMP-10",
                new BigDecimal("950000.00"),
                10,
                10
        );
        Ticket savedTicket = ticketRepository.save(ticket);
        Long ticketId = savedTicket.getId();
        String sharedKey = "idemp-" + UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest(ticketId, "user-idemp", 1, "PESSIMISTIC");
        OrderResponse first = orderService.createOrder(request, sharedKey);
        OrderResponse second = orderService.createOrder(request, sharedKey);

        Ticket finalTicket = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(first.orderNumber()).isNotBlank();
        assertThat(second.orderNumber()).isEqualTo(first.orderNumber());
        assertThat(finalTicket.getStock()).isEqualTo(9);
        assertThat(orderRepository.countByTicketId(ticketId)).isEqualTo(1);
    }
}
