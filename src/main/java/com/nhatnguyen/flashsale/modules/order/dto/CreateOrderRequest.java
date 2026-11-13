package com.nhatnguyen.flashsale.modules.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotNull(message = "Ticket ID is required")
        Long ticketId,

        @NotBlank(message = "User ID is required")
        String userId,

        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = 10, message = "Quantity cannot exceed 10")
        int quantity,

        String strategy
) {
    public CreateOrderRequest {
        if (strategy == null || strategy.isBlank()) {
            strategy = "REDIS_LUA";
        }
    }
}
