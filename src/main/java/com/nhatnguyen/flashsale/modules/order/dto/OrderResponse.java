package com.nhatnguyen.flashsale.modules.order.dto;

import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        String orderNumber,
        String userId,
        Long ticketId,
        int quantity,
        BigDecimal totalPrice,
        OrderStatus status,
        String message,
        String idempotencyKey,
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order, String message) {
        return new OrderResponse(
                order.getOrderNumber(),
                order.getUserId(),
                order.getTicketId(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                message,
                order.getIdempotencyKey(),
                order.getCreatedAt()
        );
    }

    public static OrderResponse pending(String orderNumber, String userId, Long ticketId, int quantity, BigDecimal totalPrice, String idempotencyKey) {
        return new OrderResponse(
                orderNumber,
                userId,
                ticketId,
                quantity,
                totalPrice,
                OrderStatus.PENDING,
                "Order queued successfully for asynchronous processing",
                idempotencyKey,
                LocalDateTime.now()
        );
    }
}
