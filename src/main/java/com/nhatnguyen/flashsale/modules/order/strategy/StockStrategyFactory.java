package com.nhatnguyen.flashsale.modules.order.strategy;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StockStrategyFactory {

    private final Map<StrategyType, StockDeductionStrategy> strategies = new EnumMap<>(StrategyType.class);

    public StockStrategyFactory(List<StockDeductionStrategy> strategyList) {
        for (StockDeductionStrategy strategy : strategyList) {
            strategies.put(strategy.getType(), strategy);
        }
    }

    public StockDeductionStrategy getStrategy(StrategyType type) {
        StockDeductionStrategy strategy = strategies.get(type);
        if (strategy == null) {
            return strategies.get(StrategyType.REDIS_LUA);
        }
        return strategy;
    }
}
