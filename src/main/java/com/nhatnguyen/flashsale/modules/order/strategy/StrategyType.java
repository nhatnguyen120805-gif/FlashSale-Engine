package com.nhatnguyen.flashsale.modules.order.strategy;

public enum StrategyType {
    PESSIMISTIC,
    OPTIMISTIC,
    REDIS_LUA;

    public static StrategyType fromString(String value) {
        if (value == null || value.isBlank()) {
            return REDIS_LUA;
        }
        try {
            return StrategyType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REDIS_LUA;
        }
    }
}
