package com.nhatnguyen.flashsale.modules.order.strategy;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class RedisLuaLockStrategy implements StockDeductionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisLuaLockStrategy.class);

    private static final String DEDUCT_LUA_SCRIPT = """
            local key = KEYS[1]
            local quantity = tonumber(ARGV[1])
            local current = redis.call('get', key)
            if not current then
                return -1
            end
            local stock = tonumber(current)
            if stock >= quantity then
                local remaining = redis.call('decrby', key, quantity)
                return remaining
            else
                return -2
            end
            """;

    private static final String REVERT_LUA_SCRIPT = """
            local key = KEYS[1]
            local quantity = tonumber(ARGV[1])
            return redis.call('incrby', key, quantity)
            """;

    private final StringRedisTemplate redisTemplate;
    private final TicketRepository ticketRepository;
    private final DefaultRedisScript<Long> deductScript;
    private final DefaultRedisScript<Long> revertScript;

    public RedisLuaLockStrategy(StringRedisTemplate redisTemplate, TicketRepository ticketRepository) {
        this.redisTemplate = redisTemplate;
        this.ticketRepository = ticketRepository;

        this.deductScript = new DefaultRedisScript<>();
        this.deductScript.setScriptText(DEDUCT_LUA_SCRIPT);
        this.deductScript.setResultType(Long.class);

        this.revertScript = new DefaultRedisScript<>();
        this.revertScript.setScriptText(REVERT_LUA_SCRIPT);
        this.revertScript.setResultType(Long.class);
    }

    @Override
    public StrategyType getType() {
        return StrategyType.REDIS_LUA;
    }

    @Override
    public DeductionResult deduct(Long ticketId, int quantity, String userId) {
        String redisKey = "ticket:" + ticketId + ":stock";
        List<String> keys = Collections.singletonList(redisKey);

        Long result = executeDeductScript(keys, quantity);

        // If key missing in Redis (-1), warm up cache from DB and re-execute
        if (result != null && result == -1L) {
            log.info("Redis stock key {} missing, lazy-loading from database...", redisKey);
            Ticket ticket = ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));

            redisTemplate.opsForValue().setIfAbsent(redisKey, String.valueOf(ticket.getStock()));
            result = executeDeductScript(keys, quantity);
        }

        if (result != null && result == -2L) {
            log.warn("Redis Lua: Insufficient stock for ticketId={}, requestedQuantity={}", ticketId, quantity);
            throw new OutOfStockException("Ticket " + ticketId + " is out of stock in flash-sale cache!");
        }

        if (result == null || result < 0) {
            throw new OutOfStockException("Unable to deduct stock for ticket " + ticketId + " via Redis Lua");
        }

        // Fetch ticket price for total calculation (can also be cached)
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));

        int remainingStock = result.intValue();
        log.debug("Redis Lua atomic deduction success for ticketId={}, remainingStock={}", ticketId, remainingStock);

        return DeductionResult.ok(ticketId, quantity, remainingStock, ticket.getPrice());
    }

    public void revertStock(Long ticketId, int quantity) {
        String redisKey = "ticket:" + ticketId + ":stock";
        Long restored = redisTemplate.execute(revertScript, Collections.singletonList(redisKey), String.valueOf(quantity));
        log.info("Compensated Redis stock for ticketId={}, restoredQuantity={}, currentStock={}", ticketId, quantity, restored);
    }

    private Long executeDeductScript(List<String> keys, int quantity) {
        return redisTemplate.execute(deductScript, keys, String.valueOf(quantity));
    }
}
