package com.nhatnguyen.flashsale.modules.order;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.modules.order.strategy.DeductionResult;
import com.nhatnguyen.flashsale.modules.order.strategy.RedisLuaLockStrategy;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLuaLockStrategyTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private TicketRepository ticketRepository;

    private RedisLuaLockStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RedisLuaLockStrategy(redisTemplate, ticketRepository);
    }

    @Test
    @DisplayName("Redis Lua: Successfully deducts stock atomically")
    void testRedisLuaDeductSuccess() {
        Ticket ticket = new Ticket(1L, "Festival", "FEST-1", new BigDecimal("50.00"), 100, 100);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(95L);

        DeductionResult result = strategy.deduct(1L, 5, "user-redis-1");

        assertThat(result.success()).isTrue();
        assertThat(result.remainingStock()).isEqualTo(95);
        assertThat(result.deductedQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("Redis Lua: Throws OutOfStockException when Lua returns -2 (insufficient)")
    void testRedisLuaOutOfStock() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-2L);

        assertThatThrownBy(() -> strategy.deduct(1L, 10, "user-redis-2"))
                .isInstanceOf(OutOfStockException.class)
                .hasMessageContaining("out of stock");
    }
}
