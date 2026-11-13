package com.nhatnguyen.flashsale.modules.order.strategy;

import java.math.BigDecimal;

public record DeductionResult(
        boolean success,
        Long ticketId,
        int deductedQuantity,
        int remainingStock,
        BigDecimal unitPrice,
        String message
) {
    public static DeductionResult ok(Long ticketId, int quantity, int remainingStock, BigDecimal unitPrice) {
        return new DeductionResult(true, ticketId, quantity, remainingStock, unitPrice, "Stock deducted successfully");
    }

    public static DeductionResult fail(Long ticketId, String message) {
        return new DeductionResult(false, ticketId, 0, 0, BigDecimal.ZERO, message);
    }
}
