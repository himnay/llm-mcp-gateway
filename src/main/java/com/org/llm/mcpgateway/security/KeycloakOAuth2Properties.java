package com.org.llm.mcpgateway.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-credentials settings this gateway uses to authenticate itself to Keycloak when
 * calling OAuth2-protected backends (see {@link com.org.llm.mcpgateway.config.GatewayProperties#getOauth2Backends()}).
 *
 * <pre>
 * gateway:
 *   oauth2:
 *     token-uri: ${MCP_OAUTH2_TOKEN_URI:http://localhost:8180/realms/org-mcp/protocol/openid-connect/token}
 *     client-id: ${MCP_OAUTH2_CLIENT_ID:llm-mcp-gateway}
 *     client-secret: ${MCP_OAUTH2_CLIENT_SECRET:llm-mcp-gateway-secret}
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.oauth2")
public class KeycloakOAuth2Properties {

    private String tokenUri = "http://localhost:8180/realms/org-mcp/protocol/openid-connect/token";

    private String clientId = "llm-mcp-gateway";

    private String clientSecret = "llm-mcp-gateway-secret";
}
