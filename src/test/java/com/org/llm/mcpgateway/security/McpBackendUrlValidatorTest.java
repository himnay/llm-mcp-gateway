package com.org.llm.mcpgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class McpBackendUrlValidatorTest {

    @Test
    @DisplayName("The default localhost backend URLs from application.yaml pass startup validation")
    void defaultLocalBackendsPassValidation() {
        MockEnvironment environment = new MockEnvironment();
        String[] names = {"ticket", "deployment", "notification", "hr", "github", "gmail", "travel"};
        for (int i = 0; i < names.length; i++) {
            environment.setProperty("spring.ai.mcp.client.streamable-http.connections." + names[i] + ".url",
                    "http://localhost:" + (8081 + i));
        }
        McpBackendUrlValidator validator =
                new McpBackendUrlValidator(environment, new UrlAllowlistValidator(new GatewaySsrfProperties()));

        assertDoesNotThrow(validator::validateBackendUrls);
    }
}
