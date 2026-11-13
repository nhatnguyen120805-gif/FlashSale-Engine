package com.nhatnguyen.flashsale.modules.order.consumer;

import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.event.OrderCreatedEvent;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;

    public OrderEventConsumer(OrderRepository orderRepository, TicketRepository ticketRepository) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
    }

    @RabbitListener(queues = "${flashsale.rabbitmq.queue:flashsale.order.create.queue}", ackMode = "MANUAL")
    @Transactional
    public void handleOrderCreated(@Payload OrderCreatedEvent event,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("Consumer received OrderCreatedEvent: orderNumber={}, userId={}, ticketId={}",
                event.orderNumber(), event.userId(), event.ticketId());

        try {
            // 1. Consumer Idempotency check
            if (orderRepository.findByOrderNumber(event.orderNumber()).isPresent()) {
                log.warn("Order {} already processed. Skipping duplicate message.", event.orderNumber());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. Persist Order in PostgreSQL
            Order order = new Order(
                    event.orderNumber(),
                    event.userId(),
                    event.ticketId(),
                    event.quantity(),
                    event.totalPrice(),
                    OrderStatus.CONFIRMED,
                    event.idempotencyKey()
            );
            orderRepository.save(order);

            // 3. Sync PostgreSQL ticket stock to maintain eventual consistency with Redis
            Ticket ticket = ticketRepository.findById(event.ticketId()).orElse(null);
            if (ticket != null) {
                int newStock = Math.max(0, ticket.getStock() - event.quantity());
                ticket.setStock(newStock);
                ticketRepository.save(ticket);
            }

            // 4. Acknowledge message
            channel.basicAck(deliveryTag, false);
            log.info("Order {} successfully persisted to database and ACKed.", event.orderNumber());

        } catch (Exception ex) {
            log.error("Error processing OrderCreatedEvent for orderNumber={}. Routing to DLQ.", event.orderNumber(), ex);
            try {
                // Reject and route to DLX (requeue = false)
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("Failed to basicNack message for deliveryTag={}", deliveryTag, nackEx);
            }
        }
    }
}
