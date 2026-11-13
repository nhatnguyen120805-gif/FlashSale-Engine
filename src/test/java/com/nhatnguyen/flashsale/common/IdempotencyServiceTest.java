package com.nhatnguyen.flashsale.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnguyen.flashsale.common.idempotency.IdempotencyService;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new IdempotencyService(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("Acquire returns true when key does not exist yet")
    void testAcquireSuccess() {
        String key = "test-uuid-123";
        when(valueOperations.setIfAbsent(eq("idempotency:" + key), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        boolean result = idempotencyService.acquire(key);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Acquire returns false when key is already locked/processing")
    void testAcquireDuplicateConflict() {
        String key = "test-uuid-duplicate";
        when(valueOperations.setIfAbsent(eq("idempotency:" + key), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(false);

        boolean result = idempotencyService.acquire(key);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Return cached completed response when present")
    void testGetCompletedResponse() throws Exception {
        String key = "test-uuid-cached";
        OrderResponse response = new OrderResponse(
                "ORD-999",
                "user-1",
                1L,
                2,
                new BigDecimal("599.98"),
                OrderStatus.CONFIRMED,
                "Success",
                key,
                LocalDateTime.now()
        );
        String json = objectMapper.writeValueAsString(response);

        when(valueOperations.get("idempotency:" + key)).thenReturn(json);

        Optional<OrderResponse> result = idempotencyService.getCompletedResponse(key);
        assertThat(result).isPresent();
        assertThat(result.get().orderNumber()).isEqualTo("ORD-999");
        assertThat(result.get().totalPrice()).isEqualByComparingTo("599.98");
    }

    @Test
    @DisplayName("Release deletes key from Redis")
    void testRelease() {
        String key = "test-uuid-fail";
        idempotencyService.release(key);
        verify(redisTemplate).delete("idempotency:" + key);
    }

    @Test
    @DisplayName("Local fallback prevents duplicates and caches responses when Redis is offline")
    void testLocalFallbackWhenRedisOffline() {
        String key = "key-1";
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis offline"));
        when(valueOperations.get(any()))
                .thenThrow(new RuntimeException("Redis offline"));
        org.mockito.Mockito.doThrow(new RuntimeException("Redis offline"))
                .when(valueOperations).set(any(), any(), any(Duration.class));

        assertThat(idempotencyService.acquire(key)).isTrue();
        assertThat(idempotencyService.acquire(key)).isFalse();

        OrderResponse resp = new OrderResponse(
                "ORD-LOCAL-1",
                "user-1",
                1L,
                1,
                new BigDecimal("950000"),
                OrderStatus.CONFIRMED,
                "Success",
                key,
                LocalDateTime.now()
        );
        idempotencyService.saveCompletedResponse(key, resp);

        Optional<OrderResponse> cached = idempotencyService.getCompletedResponse(key);
        assertThat(cached).isPresent();
        assertThat(cached.get().orderNumber()).isEqualTo("ORD-LOCAL-1");
    }
}
