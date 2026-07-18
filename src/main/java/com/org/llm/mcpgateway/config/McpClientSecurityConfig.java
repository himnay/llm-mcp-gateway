package com.org.llm.mcpgateway.config;

import com.org.llm.mcpgateway.security.KeycloakTokenService;
import com.org.llm.mcpgateway.web.RequestContext;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.http.HttpRequest;
import java.util.Set;

/**
 * Secures the outbound MCP connections to the backend servers. Per connection, attaches one of
 * two {@code Authorization} schemes plus the resolved acting user and correlation id for
 * server-side authorization, audit and trace stitching:
 * <ul>
 *   <li>connections listed in {@code gateway.oauth2-backends} — a client-credentials access
 *       token fetched from Keycloak via {@link KeycloakTokenService} (the backend is itself an
 *       OAuth2 resource server, e.g. {@code mcp-server-deployment-service}).</li>
 *   <li>every other connection — the legacy shared bearer token ({@code gateway.static-auth-token},
 *       bound from {@code MCP_AUTH_TOKEN}).</li>
 * </ul>
 */
@Configuration
public class McpClientSecurityConfig {

    /** Defines the mcp auth transport customizer bean. */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpAuthTransportCustomizer(
            GatewayProperties properties, KeycloakTokenService keycloakTokenService) {

        Set<String> oauth2Backends = Set.copyOf(properties.getOauth2Backends());
        McpSyncHttpClientRequestCustomizer oauth2Customizer = oauth2RequestCustomizer(keycloakTokenService);
        McpSyncHttpClientRequestCustomizer staticTokenCustomizer = staticBearerRequestCustomizer(properties);

        return (name, transportBuilder) -> transportBuilder.httpRequestCustomizer(
                oauth2Backends.contains(name) ? oauth2Customizer : staticTokenCustomizer);
    }

    private McpSyncHttpClientRequestCustomizer staticBearerRequestCustomizer(GatewayProperties properties) {
        return (builder, method, endpoint, body, context) -> {
            String token = properties.getStaticAuthToken();
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
            attachRequestMetadata(builder);
        };
    }

    private McpSyncHttpClientRequestCustomizer oauth2RequestCustomizer(KeycloakTokenService keycloakTokenService) {
        return (builder, method, endpoint, body, context) -> {
            builder.header("Authorization", "Bearer " + keycloakTokenService.getToken());
            attachRequestMetadata(builder);
        };
    }

    private static void attachRequestMetadata(HttpRequest.Builder builder) {
        String user = RequestContext.user();
        if (StringUtils.hasText(user)) {
            builder.header("X-Acting-User", user);
        }
        String correlationId = RequestContext.correlationId();
        if (StringUtils.hasText(correlationId)) {
            builder.header("X-Request-ID", correlationId);
        }
    }
}
