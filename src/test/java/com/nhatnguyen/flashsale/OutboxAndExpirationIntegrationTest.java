package com.nhatnguyen.flashsale;

import com.nhatnguyen.flashsale.common.exception.OrderStateConflictException;
import com.nhatnguyen.flashsale.common.idempotency.IdempotencyService;
import com.nhatnguyen.flashsale.modules.order.dto.CreateOrderRequest;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.order.service.OrderExpirationService;
import com.nhatnguyen.flashsale.modules.order.service.OrderService;
import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxEvent;
import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxStatus;
import com.nhatnguyen.flashsale.modules.outbox.repository.OutboxEventRepository;
import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import com.nhatnguyen.flashsale.modules.payment.dto.ConfirmPaymentRequest;
import com.nhatnguyen.flashsale.modules.payment.repository.PaymentRepository;
import com.nhatnguyen.flashsale.modules.payment.service.PaymentService;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class OutboxAndExpirationIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderExpirationService orderExpirationService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        lenient().when(valueOperations.get(any())).thenReturn(null);
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(10L);

        paymentRepository.deleteAll();
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        ticketRepository.deleteAll();
        idempotencyService.clearLocalCache();
    }

    @Test
    @DisplayName("Transactional Outbox: Order creation atomically persists PENDING order & outbox event, then relays to RabbitMQ")
    void testTransactionalOutboxEnqueueAndRelay() {
        Ticket ticket = ticketRepository.save(new Ticket(
                null, "Concert Outbox Test", "OUTBOX-1", new BigDecimal("500000.00"), 10, 10
        ));

        OrderResponse orderResponse = orderService.createOrder(
                new CreateOrderRequest(ticket.getId(), "user-outbox", 1, "PESSIMISTIC"),
                UUID.randomUUID().toString()
        );

        assertThat(orderResponse.status()).isEqualTo(OrderStatus.PENDING);

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(orderResponse.orderNumber());
        assertThat(events).hasSize(1);
        OutboxEvent pendingEvent = events.get(0);
        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pendingEvent.getEventType()).isEqualTo("ORDER_RESERVATION_CREATED");
        assertThat(pendingEvent.getRoutingKey()).isEqualTo("flashsale.order.delay");

        int published = outboxService.processPendingEvents(LocalDateTime.now().plusSeconds(1));
        assertThat(published).isEqualTo(1);

        verify(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                any(Object.class)
        );

        OutboxEvent updated = outboxEventRepository.findById(pendingEvent.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("Outbox Circuit Breaker: Exponential backoff and transition to FAILED after 3 RabbitMQ failures")
    void testOutboxCircuitBreakerAndExponentialBackoff() {
        Ticket ticket = ticketRepository.save(new Ticket(
                null, "Concert Circuit Breaker", "CB-1", new BigDecimal("500000.00"), 10, 10
        ));

        OrderResponse orderResponse = orderService.createOrder(
                new CreateOrderRequest(ticket.getId(), "user-cb", 1, "PESSIMISTIC"),
                UUID.randomUUID().toString()
        );

        OutboxEvent event = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(orderResponse.orderNumber()).get(0);

        doThrow(new RuntimeException("RabbitMQ connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        LocalDateTime t0 = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(2);
        assertThat(outboxService.processPendingEvents(t0)).isEqualTo(0);

        OutboxEvent afterRetry1 = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterRetry1.getRetryCount()).isEqualTo(1);
        assertThat(afterRetry1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterRetry1.getNextRetryAt()).isEqualTo(t0.plusSeconds(10));

        // Before backoff expires, event must NOT be picked up
        assertThat(outboxService.processPendingEvents(t0.plusSeconds(5))).isEqualTo(0);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getRetryCount()).isEqualTo(1);

        // Retry 2 after backoff
        LocalDateTime t1 = t0.plusSeconds(10);
        assertThat(outboxService.processPendingEvents(t1)).isEqualTo(0);
        OutboxEvent afterRetry2 = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterRetry2.getRetryCount()).isEqualTo(2);
        assertThat(afterRetry2.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterRetry2.getNextRetryAt()).isEqualTo(t1.plusSeconds(20));

        // Retry 3 trips circuit breaker to FAILED
        LocalDateTime t2 = t1.plusSeconds(20);
        assertThat(outboxService.processPendingEvents(t2)).isEqualTo(0);
        OutboxEvent afterRetry3 = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterRetry3.getRetryCount()).isEqualTo(3);
        assertThat(afterRetry3.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(afterRetry3.getProcessedAt()).isNotNull();
        assertThat(afterRetry3.getLastError()).contains("RabbitMQ connection refused");
    }

    @Test
    @DisplayName("Expiration & Compensation: Unpaid order expiration restores DB + Redis stock idempotently and rejects late payment")
    void testUnpaidOrderExpirationRestoresStockIdempotently() {
        Ticket ticket = ticketRepository.save(new Ticket(
                null, "Concert Expiration Test", "EXP-1", new BigDecimal("750000.00"), 10, 10
        ));

        OrderResponse order = orderService.createOrder(
                new CreateOrderRequest(ticket.getId(), "user-expire", 2, "PESSIMISTIC"),
                UUID.randomUUID().toString()
        );
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStock()).isEqualTo(8);

        // First expiration succeeds and restores 2 tickets
        boolean firstExpire = orderExpirationService.expireOrderIfUnpaid(order.orderNumber());
        assertThat(firstExpire).isTrue();
        assertThat(orderRepository.findByOrderNumber(order.orderNumber()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStock()).isEqualTo(10);
        verify(redisTemplate, atLeastOnce()).execute(any(RedisScript.class), anyList(), any());

        // Second expiration is idempotent no-op (must NOT double-restore stock)
        boolean secondExpire = orderExpirationService.expireOrderIfUnpaid(order.orderNumber());
        assertThat(secondExpire).isFalse();
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStock()).isEqualTo(10);

        // Late payment attempt must be rejected with HTTP 409 OrderStateConflictException
        assertThatThrownBy(() -> paymentService.confirmPayment(new ConfirmPaymentRequest(order.orderNumber(), "CARD")))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    @DisplayName("Race Condition Guard: 20 Virtual Threads racing Payment vs Expiration never corrupt stock or double-confirm")
    void testConcurrentPaymentVsExpirationRaceCondition() throws InterruptedException {
        Ticket ticket = ticketRepository.save(new Ticket(
                null, "Concert Race Guard", "RACE-1", new BigDecimal("990000.00"), 10, 10
        ));

        OrderResponse orderResponse = orderService.createOrder(
                new CreateOrderRequest(ticket.getId(), "user-race", 1, "PESSIMISTIC"),
                UUID.randomUUID().toString()
        );
        String orderNumber = orderResponse.orderNumber();
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStock()).isEqualTo(9);

        int paymentThreads = 10;
        int expireThreads = 10;
        int totalThreads = paymentThreads + expireThreads;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalThreads);

        AtomicInteger paymentWins = new AtomicInteger(0);
        AtomicInteger expireWins = new AtomicInteger(0);
        AtomicInteger conflictRejections = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        for (int i = 0; i < paymentThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    paymentService.confirmPayment(new ConfirmPaymentRequest(orderNumber, "MOMO"));
                    paymentWins.incrementAndGet();
                } catch (OrderStateConflictException conflict) {
                    conflictRejections.incrementAndGet();
                } catch (Exception ex) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        for (int i = 0; i < expireThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (orderExpirationService.expireOrderIfUnpaid(orderNumber)) {
                        expireWins.incrementAndGet();
                    }
                } catch (Exception ex) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();
        executor.close();

        Order finalOrder = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
        Ticket finalTicket = ticketRepository.findById(ticket.getId()).orElseThrow();
        long paymentRows = paymentRepository.count();

        assertThat(unexpectedErrors.get()).isEqualTo(0);
        assertThat(finalOrder.getStatus()).isIn(OrderStatus.CONFIRMED, OrderStatus.EXPIRED);

        if (finalOrder.getStatus() == OrderStatus.CONFIRMED) {
            assertThat(expireWins.get()).isEqualTo(0);
            assertThat(paymentWins.get()).isGreaterThanOrEqualTo(1);
            assertThat(paymentRows).isEqualTo(1);
            assertThat(finalTicket.getStock()).isEqualTo(9);
        } else {
            assertThat(expireWins.get()).isEqualTo(1);
            assertThat(paymentWins.get()).isEqualTo(0);
            assertThat(conflictRejections.get()).isEqualTo(paymentThreads);
            assertThat(paymentRows).isEqualTo(0);
            assertThat(finalTicket.getStock()).isEqualTo(10);
        }
    }
}
