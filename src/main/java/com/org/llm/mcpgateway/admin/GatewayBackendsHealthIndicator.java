package com.org.llm.mcpgateway.admin;

import com.org.llm.mcpgateway.mcp.BackendRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Surfaces downstream MCP backend reachability through {@code /actuator/health} by pinging
 * every connected backend. DOWN if any backend is unreachable, with per-backend detail.
 */
@Component
@RequiredArgsConstructor
public class GatewayBackendsHealthIndicator implements HealthIndicator {

    private final BackendRegistry backendRegistry;

    @Override
    public Health health() {
        Map<String, String> details = backendRegistry.health();
        boolean allUp = details.values().stream().allMatch("UP"::equals);
        return (allUp ? Health.up() : Health.down()).withDetails(details).build();
    }
}
