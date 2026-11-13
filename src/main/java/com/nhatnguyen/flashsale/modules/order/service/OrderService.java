package com.nhatnguyen.flashsale.modules.order.service;

import com.nhatnguyen.flashsale.common.exception.IdempotencyConflictException;
import com.nhatnguyen.flashsale.common.idempotency.IdempotencyService;
import com.nhatnguyen.flashsale.modules.order.dto.CreateOrderRequest;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.event.OrderCreatedEvent;
import com.nhatnguyen.flashsale.modules.order.producer.OrderEventProducer;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.order.strategy.DeductionResult;
import com.nhatnguyen.flashsale.modules.order.strategy.StockDeductionStrategy;
import com.nhatnguyen.flashsale.modules.order.strategy.StockStrategyFactory;
import com.nhatnguyen.flashsale.modules.order.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public OrderService(StockStrategyFactory strategyFactory,
                        OrderRepository orderRepository,
                        OrderEventProducer orderEventProducer,
                        IdempotencyService idempotencyService) {
        this.strategyFactory = strategyFactory;
        this.orderRepository = orderRepository;
        this.orderEventProducer = orderEventProducer;
        this.idempotencyService = idempotencyService;
    }

    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<OrderResponse> cached = idempotencyService.getCompletedResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Idempotency HIT: Returning cached order for key={}", idempotencyKey);
                return cached.get();
            }

            boolean acquired = idempotencyService.acquire(idempotencyKey);
            if (!acquired) {
                log.warn("Idempotency CONFLICT: Key {} is currently being processed", idempotencyKey);
                throw new IdempotencyConflictException("Order processing already in flight for key: " + idempotencyKey);
            }
        }

        try {
            // 2. Resolve Strategy
            StrategyType strategyType = StrategyType.fromString(request.strategy());
            StockDeductionStrategy strategy = strategyFactory.getStrategy(strategyType);

            // 3. Deduct Stock
            DeductionResult deductionResult = strategy.deduct(request.ticketId(), request.quantity(), request.userId());

            String orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            BigDecimal totalPrice = deductionResult.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

            OrderResponse response;

            // 4. Persistence Dispatch
            if (strategyType == StrategyType.REDIS_LUA) {
                // Async processing via RabbitMQ
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
                orderEventProducer.publishOrderCreated(event);
                response = OrderResponse.pending(orderNumber, request.userId(), request.ticketId(), request.quantity(), totalPrice, idempotencyKey);
            } else {
                // Synchronous database commit for Pessimistic / Optimistic benchmarking
                Order order = new Order(
                        orderNumber,
                        request.userId(),
                        request.ticketId(),
                        request.quantity(),
                        totalPrice,
                        OrderStatus.CONFIRMED,
                        idempotencyKey
                );
                Order saved = orderRepository.save(order);
                response = OrderResponse.from(saved, "Order placed successfully via " + strategyType);
            }

            // 5. Store completed response in idempotency cache
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.saveCompletedResponse(idempotencyKey, response);
            }

            return response;

        } catch (Exception ex) {
            // Release idempotency lock if deduction failed
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.release(idempotencyKey);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(o -> OrderResponse.from(o, "Order found"));
    }
}
