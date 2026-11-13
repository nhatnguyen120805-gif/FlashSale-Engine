package com.nhatnguyen.flashsale.modules.order.strategy;

public interface StockDeductionStrategy {

    StrategyType getType();

    DeductionResult deduct(Long ticketId, int quantity, String userId);
}
