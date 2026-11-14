package com.nhatnguyen.flashsale.modules.order.service;

import com.nhatnguyen.flashsale.common.metrics.FlashSaleMetrics;
import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.event.OrderExpirationEvent;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.order.strategy.RedisLuaLockStrategy;
import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class OrderExpirationService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationService.class);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final RedisLuaLockStrategy redisLuaLockStrategy;
    private final OutboxService outboxService;
    private final FlashSaleMetrics flashSaleMetrics;

    @Value("${flashsale.rabbitmq.exchange:flashsale.order.exchange}")
    private String orderExchangeName;

    public OrderExpirationService(OrderRepository orderRepository,
                                  TicketRepository ticketRepository,
                                  RedisLuaLockStrategy redisLuaLockStrategy,
                                  OutboxService outboxService,
                                  FlashSaleMetrics flashSaleMetrics) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.redisLuaLockStrategy = redisLuaLockStrategy;
        this.outboxService = outboxService;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    @Transactional
    public boolean expireOrderIfUnpaid(String orderNumber) {
        Optional<Order> orderOpt = orderRepository.findByOrderNumber(orderNumber);
        if (orderOpt.isEmpty()) {
            log.warn("Expiration skipped: orderNumber={} not found in database", orderNumber);
            return false;
        }

        Order order = orderOpt.get();
        int updated = orderRepository.transitionStatusAtomically(
                orderNumber,
                OrderStatus.PENDING,
                OrderStatus.EXPIRED
        );

        if (updated == 1) {
            ticketRepository.restoreStockDirect(order.getTicketId(), order.getQuantity());
            redisLuaLockStrategy.revertStock(order.getTicketId(), order.getQuantity());

            outboxService.enqueue(
                    "ORDER",
                    orderNumber,
                    "ORDER_EXPIRED_COMPENSATED",
                    orderExchangeName,
                    "flashsale.order.compensated",
                    new OrderExpirationEvent(orderNumber, order.getTicketId(), order.getQuantity(), System.currentTimeMillis())
            );

            flashSaleMetrics.recordOrderExpiredCompensated();
            log.info("Order {} expired due to unpaid TTL; restored {} ticket(s) to ticketId={}",
                    orderNumber, order.getQuantity(), order.getTicketId());
            return true;
        }

        log.info("Expiration no-op for orderNumber={}: already transitioned out of PENDING", orderNumber);
        return false;
    }
}
