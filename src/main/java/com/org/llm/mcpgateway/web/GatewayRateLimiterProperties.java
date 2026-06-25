package com.org.llm.mcpgateway.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <pre>
 * gateway:
 *   rate-limiter:
 *     enabled: true   # disable in tests that don't run Redis
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.rate-limiter")
public class GatewayRateLimiterProperties {

    private boolean enabled = true;
}
