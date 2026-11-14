package com.nhatnguyen.flashsale.modules.order.event;

import java.io.Serializable;

public record OrderExpirationEvent(
        String orderNumber,
        Long ticketId,
        int quantity,
        long timestamp
) implements Serializable {
}
