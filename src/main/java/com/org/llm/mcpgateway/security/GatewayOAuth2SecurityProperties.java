package com.org.llm.mcpgateway.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound OAuth2 resource-server settings — what callers must present to reach this gateway's
 * {@code /mcp/**} and {@code /gateway/**} endpoints.
 *
 * <pre>
 * gateway:
 *   security:
 *     oauth2:
 *       enabled: true
 *       required-scope: gateway-invoke
 *       required-audience: mcp-gateway
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.security.oauth2")
public class GatewayOAuth2SecurityProperties {

    /**
     * Kill switch — set to {@code false} for local dev/tests without a running Keycloak.
     */
    private boolean enabled = true;

    /**
     * OAuth2 scope every inbound caller's token must carry (mapped by Spring Security's
     * default JWT converter to the {@code SCOPE_<value>} authority).
     */
    private String requiredScope = "gateway-invoke";

    /**
     * Audience ({@code aud} claim) the token must carry, so a token minted for a different
     * MCP server can't be replayed against this gateway.
     */
    private String requiredAudience = "mcp-gateway";
}
