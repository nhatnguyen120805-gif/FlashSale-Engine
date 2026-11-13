package com.nhatnguyen.flashsale.common;

import com.nhatnguyen.flashsale.common.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(redisTemplate);
    }

    @Test
    @DisplayName("Rate limiter allows request when tokens are available")
    void testAllowRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);

        boolean allowed = rateLimiterService.tryAcquire("192.168.1.100");
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Rate limiter rejects request when token bucket is exhausted")
    void testRejectRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);

        boolean allowed = rateLimiterService.tryAcquire("192.168.1.100");
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Fail open when Redis throws unexpected connection error")
    void testFailOpenOnRedisError() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        boolean allowed = rateLimiterService.tryAcquire("192.168.1.100");
        assertThat(allowed).isTrue();
    }
}
