package com.nhatnguyen.flashsale.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])

            local data = redis.call('hmget', key, 'tokens', 'last_updated')
            local tokens = tonumber(data[1])
            local last_updated = tonumber(data[2])

            if not tokens then
                tokens = capacity
                last_updated = now
            else
                local delta = math.max(0, now - last_updated)
                tokens = math.min(capacity, tokens + (delta * refill_rate))
                last_updated = now
            end

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('hmset', key, 'tokens', tokens, 'last_updated', last_updated)
                redis.call('expire', key, 60)
                return 1
            else
                redis.call('hmset', key, 'tokens', tokens, 'last_updated', last_updated)
                redis.call('expire', key, 60)
                return 0
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    @Value("${flashsale.rate-limit.capacity:20}")
    private long defaultCapacity = 20;

    @Value("${flashsale.rate-limit.refill-tokens-per-second:10}")
    private long defaultRefillRate = 10;

    private final java.util.concurrent.ConcurrentMap<String, LocalTokenBucket> localBuckets = new java.util.concurrent.ConcurrentHashMap<>();

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(TOKEN_BUCKET_LUA);
        this.rateLimitScript.setResultType(Long.class);
    }

    public boolean tryAcquire(String clientIdentifier) {
        return tryAcquire(clientIdentifier, defaultCapacity, defaultRefillRate, 1);
    }

    public boolean tryAcquire(String clientIdentifier, long capacity, long refillRate, int requestedTokens) {
        String key = "ratelimit:" + clientIdentifier;
        long now = Instant.now().getEpochSecond();

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(requestedTokens),
                    String.valueOf(now)
            );
            return result != null && result == 1L;
        } catch (Exception ex) {
            log.warn("RateLimiter Redis failure, falling back to local token bucket for client={}: {}", clientIdentifier, ex.getMessage());
            return tryAcquireLocal(clientIdentifier, capacity);
        }
    }

    private boolean tryAcquireLocal(String clientIdentifier, long capacity) {
        LocalTokenBucket bucket = localBuckets.computeIfAbsent(clientIdentifier, k -> new LocalTokenBucket(capacity));
        return bucket.tryConsume();
    }

    private static class LocalTokenBucket {
        private final long capacity;
        private double tokens;
        private long lastRefillTime;

        public LocalTokenBucket(long capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double seconds = (now - lastRefillTime) / 1000.0;
            tokens = Math.min(capacity, tokens + seconds * 5.0); // 5 tokens/sec
            lastRefillTime = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
