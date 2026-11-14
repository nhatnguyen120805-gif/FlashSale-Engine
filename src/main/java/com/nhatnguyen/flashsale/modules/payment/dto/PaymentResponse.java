package com.nhatnguyen.flashsale.modules.payment.dto;

import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.payment.entity.Payment;
import com.nhatnguyen.flashsale.modules.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        String transactionId,
        String orderNumber,
        BigDecimal amount,
        PaymentStatus status,
        OrderStatus orderStatus,
        String message,
        LocalDateTime createdAt
) {
    public static PaymentResponse from(Payment payment, OrderStatus orderStatus, String message) {
        return new PaymentResponse(
                payment.getTransactionId(),
                payment.getOrderNumber(),
                payment.getAmount(),
                payment.getStatus(),
                orderStatus,
                message,
                payment.getCreatedAt()
        );
    }
}
