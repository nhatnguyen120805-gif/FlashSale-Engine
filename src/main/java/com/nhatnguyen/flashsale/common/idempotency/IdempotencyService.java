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

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String PROCESSING_STATUS = "PROCESSING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flashsale.idempotency.ttl-seconds:86400}")
    private long idempotencyTtlSeconds;

    public IdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean acquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true; // No key provided, bypass idempotency lock
        }
        String redisKey = "idempotency:" + idempotencyKey;
        // Acquire lock with 60s timeout to handle crash recovery
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING_STATUS, Duration.ofSeconds(60));
        return Boolean.TRUE.equals(acquired);
    }

    public boolean isProcessing(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        String redisKey = "idempotency:" + idempotencyKey;
        String val = redisTemplate.opsForValue().get(redisKey);
        return PROCESSING_STATUS.equals(val);
    }

    public Optional<OrderResponse> getCompletedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String redisKey = "idempotency:" + idempotencyKey;
        String val = redisTemplate.opsForValue().get(redisKey);
        if (val == null || PROCESSING_STATUS.equals(val)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(val, OrderResponse.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached idempotency response for key={}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    public void saveCompletedResponse(String idempotencyKey, OrderResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        String redisKey = "idempotency:" + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(idempotencyTtlSeconds));
            log.debug("Saved idempotency result for key={}, TTL={}s", idempotencyKey, idempotencyTtlSeconds);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize idempotency response for key={}", idempotencyKey, e);
        }
    }

    public void release(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redisTemplate.delete("idempotency:" + idempotencyKey);
            log.debug("Released idempotency key={} after failure", idempotencyKey);
        }
    }
}
