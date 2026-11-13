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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

        try {
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

            // Fetch ticket price from Redis cache to avoid database hit on fast-path
            BigDecimal unitPrice = getCachedPrice(ticketId);

            int remainingStock = result.intValue();
            log.debug("Redis Lua atomic deduction success for ticketId={}, remainingStock={}", ticketId, remainingStock);

            return DeductionResult.ok(ticketId, quantity, remainingStock, unitPrice);
        } catch (OutOfStockException | TicketNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Redis unreachable during RedisLuaLockStrategy.deduct, falling back to atomic DB deduction for ticketId={}: {}",
                    ticketId, ex.getMessage());
            return deductLocalFallback(ticketId, quantity);
        }
    }

    @Transactional
    protected DeductionResult deductLocalFallback(Long ticketId, int quantity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));
        if (ticket.getStock() < quantity) {
            throw new OutOfStockException("Ticket " + ticketId + " is out of stock! Current stock: " + ticket.getStock());
        }
        int updated = ticketRepository.deductStockDirect(ticketId, quantity);
        if (updated == 0) {
            throw new OutOfStockException("Ticket " + ticketId + " is out of stock!");
        }
        int remaining = Math.max(0, ticket.getStock() - quantity);
        return DeductionResult.ok(ticketId, quantity, remaining, ticket.getPrice());
    }

    private BigDecimal getCachedPrice(Long ticketId) {
        String priceKey = "ticket:" + ticketId + ":price";
        try {
            String cachedPrice = redisTemplate.opsForValue().get(priceKey);
            if (cachedPrice != null && !cachedPrice.isBlank()) {
                return new BigDecimal(cachedPrice);
            }
        } catch (Exception ex) {
            log.debug("Redis price cache miss or error for ticketId={}: {}", ticketId, ex.getMessage());
        }

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));
        BigDecimal price = ticket.getPrice();
        try {
            redisTemplate.opsForValue().set(priceKey, price.toPlainString());
        } catch (Exception ignored) {
            // Non-critical cache write error
        }
        return price;
    }

    public void revertStock(Long ticketId, int quantity) {
        String redisKey = "ticket:" + ticketId + ":stock";
        try {
            Long restored = redisTemplate.execute(revertScript, Collections.singletonList(redisKey), String.valueOf(quantity));
            log.info("Compensated Redis stock for ticketId={}, restoredQuantity={}, currentStock={}", ticketId, quantity, restored);
        } catch (Exception ex) {
            log.warn("Failed to revert Redis stock for ticketId={} (Redis may be offline): {}", ticketId, ex.getMessage());
        }
    }

    private Long executeDeductScript(List<String> keys, int quantity) {
        return redisTemplate.execute(deductScript, keys, String.valueOf(quantity));
    }
}
