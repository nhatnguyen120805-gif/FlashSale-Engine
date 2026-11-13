package com.nhatnguyen.flashsale.modules.order.producer;

import com.nhatnguyen.flashsale.modules.order.event.OrderCreatedEvent;
import com.nhatnguyen.flashsale.modules.order.strategy.RedisLuaLockStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    private final RabbitTemplate rabbitTemplate;
    private final RedisLuaLockStrategy redisLuaLockStrategy;

    @Value("${flashsale.rabbitmq.exchange:flashsale.order.exchange}")
    private String exchange;

    @Value("${flashsale.rabbitmq.routing-key:flashsale.order.create}")
    private String routingKey;

    public OrderEventProducer(RabbitTemplate rabbitTemplate, RedisLuaLockStrategy redisLuaLockStrategy) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisLuaLockStrategy = redisLuaLockStrategy;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent to RabbitMQ: orderNumber={}, userId={}, ticketId={}",
                event.orderNumber(), event.userId(), event.ticketId());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event);
        } catch (Exception ex) {
            log.error("Failed to publish OrderCreatedEvent for orderNumber={}. Triggering Redis stock compensation!",
                    event.orderNumber(), ex);
            // Compensate Redis stock to prevent ghost loss
            redisLuaLockStrategy.revertStock(event.ticketId(), event.quantity());
            throw new RuntimeException("Message publishing failed; stock reverted safely", ex);
        }
    }
}
