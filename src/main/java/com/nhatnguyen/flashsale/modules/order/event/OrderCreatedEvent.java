package com.nhatnguyen.flashsale.modules.order.event;

import java.io.Serializable;
import java.math.BigDecimal;

public record OrderCreatedEvent(
        String orderNumber,
        String userId,
        Long ticketId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        String idempotencyKey,
        long timestamp
) implements Serializable {
}
