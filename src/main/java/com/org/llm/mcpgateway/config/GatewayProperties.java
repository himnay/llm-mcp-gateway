package com.org.llm.mcpgateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-cutting gateway knobs bound from the {@code gateway.*} prefix. Backend connection
 * names referenced here (e.g. in {@link #oauth2Backends}) must match the keys under
 * {@code spring.ai.mcp.client.streamable-http.connections}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /**
     * Backend connection names that are themselves OAuth2 resource servers and require a
     * Keycloak client-credentials bearer token. Every other connection falls back to
     * {@link #staticAuthToken}.
     */
    private List<String> oauth2Backends = new ArrayList<>(List.of("deployment"));

    /**
     * Shared bearer token forwarded to legacy (non-OAuth2) backends. Bound from MCP_AUTH_TOKEN.
     */
    private String staticAuthToken = "";

    /**
     * Fallback acting user when no authenticated principal / X-Acting-User header is present.
     */
    private String defaultUser = "system";

    /**
     * Per-user tool-call requests allowed per minute.
     */
    private int rateLimitPerMinute = 120;

    /**
     * Stricter per-user limit applied to write/destructive tool calls.
     */
    private int writeRateLimitPerMinute = 10;

    /**
     * Hard timeout for a single backend tool invocation.
     */
    private int toolTimeoutSeconds = 30;

    /**
     * Max characters of a single tool result returned to the caller.
     */
    private int maxToolResultChars = 8000;

    /**
     * Tool-name substrings treated as write/destructive for rate-limiting purposes.
     */
    private List<String> writeToolKeywords = new ArrayList<>(List.of(
            "apply", "create", "update", "delete", "send", "deploy",
            "trigger", "rollback", "cancel", "remove", "approve", "assign", "reschedule"));

    /**
     * Description overrides for existing tools, keyed by tool name — lets a workflow-specific
     * description replace a backend's generic one without changing the backend.
     */
    private Map<String, ToolOverride> toolOverrides = new LinkedHashMap<>();

    /**
     * New tools, keyed by their new name, each wrapping an existing tool ({@code baseTool}) with
     * a refined description and a fixed set of arguments merged into every call.
     */
    private Map<String, DerivedTool> derivedTools = new LinkedHashMap<>();
}
