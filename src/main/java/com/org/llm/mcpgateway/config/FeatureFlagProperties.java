package com.org.llm.mcpgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureFlagProperties {
    private boolean rateLimitingEnabled = true;
    private boolean oauth2AuthEnabled = true;
    private boolean toolArgumentGuardEnabled = true;
    private boolean auditLoggingEnabled = true;
    private boolean derivedToolsEnabled = true;
}
