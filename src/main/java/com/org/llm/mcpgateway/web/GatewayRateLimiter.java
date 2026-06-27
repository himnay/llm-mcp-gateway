package com.org.llm.mcpgateway.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed sliding-window rate limiter, keyed per acting user so the limit holds across
 * gateway instances. Fails open on Redis errors — an unavailable Redis must not block MCP
 * traffic, it only loses the rate-limit safety net temporarily.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.rate-limiter", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class GatewayRateLimiter {

    private static final String KEY_PREFIX = "rl:mcp-gateway:";
    private static final long WINDOW_SECONDS = 60;

    private final StringRedisTemplate redis;

    public boolean tryAcquire(String user, int limitPerWindow) {
        String key = KEY_PREFIX + user + ":" + (Instant.now().getEpochSecond() / WINDOW_SECONDS);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS * 2));
            }
            return count == null || count <= limitPerWindow;
        } catch (Exception ex) {
            log.error("RATE_LIMIT | Redis error, allowing request | user={} | {}", user, ex.getMessage());
            return true;
        }
    }
}
