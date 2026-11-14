package com.nhatnguyen.flashsale.modules.order.consumer;

import com.nhatnguyen.flashsale.modules.order.event.OrderExpirationEvent;
import com.nhatnguyen.flashsale.modules.order.service.OrderExpirationService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationConsumer.class);

    private final OrderExpirationService orderExpirationService;

    public OrderExpirationConsumer(OrderExpirationService orderExpirationService) {
        this.orderExpirationService = orderExpirationService;
    }

    @RabbitListener(queues = "${flashsale.rabbitmq.expired-queue:flashsale.order.expired.queue}", ackMode = "MANUAL")
    public void handleOrderExpired(@Payload OrderExpirationEvent event,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("Received expired TTL event for orderNumber={}, ticketId={}, quantity={}",
                event.orderNumber(), event.ticketId(), event.quantity());
        try {
            orderExpirationService.expireOrderIfUnpaid(event.orderNumber());
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("Failed to process expiration event for orderNumber={}", event.orderNumber(), ex);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("Failed to basicNack expiration deliveryTag={}", deliveryTag, nackEx);
            }
        }
    }
}
