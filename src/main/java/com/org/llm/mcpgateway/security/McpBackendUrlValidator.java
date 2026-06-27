package com.org.llm.mcpgateway.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates all backend MCP server URLs at startup to prevent SSRF.
 * The URLs are read directly from the environment (Spring AI MCP client configuration)
 * so they are checked before any connection attempt is made.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpBackendUrlValidator {

    private static final List<String> BACKEND_URL_PROPERTIES = List.of(
            "spring.ai.mcp.client.streamable-http.connections.ticket.url",
            "spring.ai.mcp.client.streamable-http.connections.deployment.url",
            "spring.ai.mcp.client.streamable-http.connections.notification.url",
            "spring.ai.mcp.client.streamable-http.connections.hr.url",
            "spring.ai.mcp.client.streamable-http.connections.github.url",
            "spring.ai.mcp.client.streamable-http.connections.gmail.url",
            "spring.ai.mcp.client.streamable-http.connections.travel.url"
    );

    private final Environment environment;
    private final UrlAllowlistValidator urlAllowlistValidator;

    @PostConstruct
    void validateBackendUrls() {
        for (String property : BACKEND_URL_PROPERTIES) {
            String url = environment.getProperty(property);
            if (url != null) {
                urlAllowlistValidator.validate(url, property);
                log.info("SSRF | backend URL validated: {} = {}", property, url);
            }
        }
    }
}
