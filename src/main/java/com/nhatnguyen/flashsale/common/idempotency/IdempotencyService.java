package com.nhatnguyen.flashsale.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String PROCESSING_STATUS = "PROCESSING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Boolean> localLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OrderResponse> localCompleted = new ConcurrentHashMap<>();

    @Value("${flashsale.idempotency.ttl-seconds:86400}")
    private long idempotencyTtlSeconds;

    public IdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean acquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        try {
            String redisKey = "idempotency:" + idempotencyKey;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING_STATUS, Duration.ofSeconds(60));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("Redis unreachable in IdempotencyService.acquire, using local fallback: {}", ex.getMessage());
            if (localCompleted.containsKey(idempotencyKey)) {
                return false;
            }
            return localLocks.putIfAbsent(idempotencyKey, Boolean.TRUE) == null;
        }
    }

    public boolean isProcessing(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        try {
            String redisKey = "idempotency:" + idempotencyKey;
            String val = redisTemplate.opsForValue().get(redisKey);
            return PROCESSING_STATUS.equals(val);
        } catch (Exception ex) {
            log.warn("Redis unreachable in IdempotencyService.isProcessing: {}", ex.getMessage());
            return localLocks.containsKey(idempotencyKey) && !localCompleted.containsKey(idempotencyKey);
        }
    }

    public Optional<OrderResponse> getCompletedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        try {
            String redisKey = "idempotency:" + idempotencyKey;
            String val = redisTemplate.opsForValue().get(redisKey);
            if (val == null || PROCESSING_STATUS.equals(val)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(val, OrderResponse.class));
        } catch (Exception ex) {
            log.warn("Redis unreachable in IdempotencyService.getCompletedResponse: {}", ex.getMessage());
            return Optional.ofNullable(localCompleted.get(idempotencyKey));
        }
    }

    public void saveCompletedResponse(String idempotencyKey, OrderResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            String redisKey = "idempotency:" + idempotencyKey;
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(idempotencyTtlSeconds));
            log.debug("Saved idempotency result for key={}, TTL={}s", idempotencyKey, idempotencyTtlSeconds);
        } catch (Exception ex) {
            log.warn("Redis unreachable in IdempotencyService.saveCompletedResponse: {}", ex.getMessage());
            localCompleted.put(idempotencyKey, response);
            localLocks.remove(idempotencyKey);
        }
    }

    public void release(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                redisTemplate.delete("idempotency:" + idempotencyKey);
                log.debug("Released idempotency key={} after failure", idempotencyKey);
            } catch (Exception ex) {
                log.warn("Redis unreachable in IdempotencyService.release: {}", ex.getMessage());
                localLocks.remove(idempotencyKey);
            }
        }
    }
}
