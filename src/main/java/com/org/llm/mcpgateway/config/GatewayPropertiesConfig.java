package com.org.llm.mcpgateway.config;

import com.org.llm.mcpgateway.guardrail.PiiRedactionProperties;
import com.org.llm.mcpgateway.security.GatewayOAuth2SecurityProperties;
import com.org.llm.mcpgateway.security.KeycloakOAuth2Properties;
import com.org.llm.mcpgateway.web.GatewayRateLimiterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Single place where every {@code @ConfigurationProperties} class is registered, so each
 * properties class itself stays a plain POJO without a stray {@code @Component}.
 */
@Configuration
@EnableConfigurationProperties({
        GatewayProperties.class,
        GatewayOAuth2SecurityProperties.class,
        KeycloakOAuth2Properties.class,
        GatewayRateLimiterProperties.class,
        PiiRedactionProperties.class
})
public class GatewayPropertiesConfig {
}
