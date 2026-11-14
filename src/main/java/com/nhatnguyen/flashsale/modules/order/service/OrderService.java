package com.nhatnguyen.flashsale.modules.order.service;

import com.nhatnguyen.flashsale.common.exception.IdempotencyConflictException;
import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.common.idempotency.IdempotencyService;
import com.nhatnguyen.flashsale.common.metrics.FlashSaleMetrics;
import com.nhatnguyen.flashsale.modules.order.dto.CreateOrderRequest;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.event.OrderCreatedEvent;
import com.nhatnguyen.flashsale.modules.order.event.OrderExpirationEvent;
import com.nhatnguyen.flashsale.modules.order.producer.OrderEventProducer;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.order.strategy.DeductionResult;
import com.nhatnguyen.flashsale.modules.order.strategy.StockDeductionStrategy;
import com.nhatnguyen.flashsale.modules.order.strategy.StockStrategyFactory;
import com.nhatnguyen.flashsale.modules.order.strategy.StrategyType;
import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final StockStrategyFactory strategyFactory;
    private final OrderRepository orderRepository;
    private final OrderEventProducer orderEventProducer;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final FlashSaleMetrics flashSaleMetrics;

    @Value("${flashsale.rabbitmq.exchange:flashsale.order.exchange}")
    private String orderExchangeName;

    @Value("${flashsale.rabbitmq.delay-routing-key:flashsale.order.delay}")
    private String delayRoutingKey;

    public OrderService(StockStrategyFactory strategyFactory,
                        OrderRepository orderRepository,
                        OrderEventProducer orderEventProducer,
                        IdempotencyService idempotencyService,
                        OutboxService outboxService,
                        TransactionTemplate transactionTemplate,
                        FlashSaleMetrics flashSaleMetrics) {
        this.strategyFactory = strategyFactory;
        this.orderRepository = orderRepository;
        this.orderEventProducer = orderEventProducer;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.transactionTemplate = transactionTemplate;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<OrderResponse> cached = idempotencyService.getCompletedResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Idempotency HIT: Returning cached order for key={}", idempotencyKey);
                return cached.get();
            }

            Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOrder.isPresent()) {
                log.info("Idempotency DB HIT: Returning existing persisted order for key={}", idempotencyKey);
                OrderResponse dbResponse = OrderResponse.from(existingOrder.get(), "Order retrieved via idempotency key");
                idempotencyService.saveCompletedResponse(idempotencyKey, dbResponse);
                return dbResponse;
            }

            boolean acquired = idempotencyService.acquire(idempotencyKey);
            if (!acquired) {
                log.warn("Idempotency CONFLICT: Key {} is currently being processed", idempotencyKey);
                throw new IdempotencyConflictException("Order processing already in flight for key: " + idempotencyKey);
            }
        }

        long startNanos = System.nanoTime();
        try {
            // 2. Resolve Strategy
            StrategyType strategyType = StrategyType.fromString(request.strategy());
            StockDeductionStrategy strategy = strategyFactory.getStrategy(strategyType);

            // 3. Deduct Stock
            DeductionResult deductionResult;
            try {
                deductionResult = strategy.deduct(request.ticketId(), request.quantity(), request.userId());
            } finally {
                flashSaleMetrics.recordDeductionLatencyNanos(System.nanoTime() - startNanos);
            }

            String orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            BigDecimal totalPrice = deductionResult.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

            OrderResponse response;

            // 4. Persistence Dispatch
            if (strategyType == StrategyType.REDIS_LUA) {
                OrderCreatedEvent event = new OrderCreatedEvent(
                        orderNumber,
                        request.userId(),
                        request.ticketId(),
                        request.quantity(),
                        deductionResult.unitPrice(),
                        totalPrice,
                        idempotencyKey,
                        System.currentTimeMillis()
                );
                try {
                    orderEventProducer.publishOrderCreated(event);
                    response = OrderResponse.pending(orderNumber, request.userId(), request.ticketId(), request.quantity(), totalPrice, idempotencyKey);
                } catch (Exception mqEx) {
                    log.warn("RabbitMQ publish failed for orderNumber={}, falling back to synchronous DB persistence: {}",
                            orderNumber, mqEx.getMessage());
                    Order saved = persistPendingOrderWithOutbox(orderNumber, request, totalPrice, idempotencyKey);
                    response = OrderResponse.from(saved, "Order reserved via REDIS_LUA (local sync fallback; awaiting payment)");
                }
            } else {
                // Synchronous database commit for Pessimistic / Optimistic benchmarking
                Order saved = persistPendingOrderWithOutbox(orderNumber, request, totalPrice, idempotencyKey);
                response = OrderResponse.from(saved, "Order reserved via " + strategyType + " (awaiting payment within TTL)");
            }

            flashSaleMetrics.recordOrderCreated();

            // 5. Store completed response in idempotency cache
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.saveCompletedResponse(idempotencyKey, response);
            }

            return response;

        } catch (Exception ex) {
            if (ex instanceof OutOfStockException) {
                flashSaleMetrics.recordOrderOutOfStock();
            }
            // Release idempotency lock if deduction failed
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.release(idempotencyKey);
            }
            throw ex;
        }
    }

    private Order persistPendingOrderWithOutbox(String orderNumber,
                                                CreateOrderRequest request,
                                                BigDecimal totalPrice,
                                                String idempotencyKey) {
        return transactionTemplate.execute(status -> {
            Order order = new Order(
                    orderNumber,
                    request.userId(),
                    request.ticketId(),
                    request.quantity(),
                    totalPrice,
                    OrderStatus.PENDING,
                    idempotencyKey
            );
            Order saved = orderRepository.save(order);
            outboxService.enqueue(
                    "ORDER",
                    orderNumber,
                    "ORDER_RESERVATION_CREATED",
                    orderExchangeName,
                    delayRoutingKey,
                    new OrderExpirationEvent(orderNumber, request.ticketId(), request.quantity(), System.currentTimeMillis())
            );
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(o -> OrderResponse.from(o, "Order found"));
    }
}
