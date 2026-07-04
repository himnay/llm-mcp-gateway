package com.org.llm.mcpgateway.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisReturning(long countAfterIncrement) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(countAfterIncrement);
        return redis;
    }

    @Test
    @DisplayName("Allows request when count is under the limit")
    void allowsRequestUnderTheLimit() {
        GatewayRateLimiter limiter = new GatewayRateLimiter(redisReturning(5));
        assertThat(limiter.tryAcquire("alice", 10)).isTrue();
    }

    @Test
    @DisplayName("Blocks request when count exceeds the limit")
    void blocksRequestOverTheLimit() {
        GatewayRateLimiter limiter = new GatewayRateLimiter(redisReturning(11));
        assertThat(limiter.tryAcquire("alice", 10)).isFalse();
    }

    @Test
    @DisplayName("Allows request when count is exactly at the limit")
    void allowsExactlyAtTheLimit() {
        GatewayRateLimiter limiter = new GatewayRateLimiter(redisReturning(10));
        assertThat(limiter.tryAcquire("alice", 10)).isTrue();
    }

    @Test
    @DisplayName("Fails open and allows the request when Redis throws a connection error")
    void failsOpenWhenRedisErrors() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        GatewayRateLimiter limiter = new GatewayRateLimiter(redis);
        assertThat(limiter.tryAcquire("alice", 1)).isTrue();
    }
}
