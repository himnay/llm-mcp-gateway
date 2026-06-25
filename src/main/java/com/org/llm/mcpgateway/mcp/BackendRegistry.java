package com.org.llm.mcpgateway.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connects to every configured backend MCP server (best-effort — an unreachable backend is
 * skipped, not fatal, so the gateway starts with whatever subset is currently up) and builds the
 * tool-name → backend-name map used to resolve which circuit breaker / retry / audit label a
 * tool call belongs to. This map is derived purely from each backend's own
 * {@code listTools()} response, so adding a backend or a tool needs no change here.
 */
@Slf4j
@Component
public class BackendRegistry {

    private final List<McpSyncClient> allClients;
    private final Map<String, McpSyncClient> clientsByName = new LinkedHashMap<>();
    // LinkedHashMap (not ConcurrentHashMap) so /gateway/backends reports tools in a stable,
    // deterministic discovery order; writes only happen once during connect(), so a
    // synchronized wrapper is enough to make later concurrent reads safe.
    private final Map<String, String> toolToBackend = Collections.synchronizedMap(new LinkedHashMap<>());

    public BackendRegistry(ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider) {
        this.allClients = mcpSyncClientsProvider.stream().flatMap(List::stream).toList();
    }

    @PostConstruct
    void connect() {
        for (McpSyncClient client : allClients) {
            String name = clientName(client);
            try {
                client.initialize();
                clientsByName.put(name, client);
                List<String> tools = client.listTools().tools().stream().map(t -> t.name()).toList();
                tools.forEach(toolName -> toolToBackend.put(toolName, name));
                log.info("MCP backend connected: {} ({} tools)", name, tools.size());
            } catch (Exception e) {
                log.warn("MCP backend unavailable, skipping: {} — {}", name, e.getMessage());
            }
        }
        if (clientsByName.isEmpty()) {
            log.warn("No MCP backends are reachable — the gateway will expose no tools");
        }
    }

    public List<McpSyncClient> availableClients() {
        return List.copyOf(clientsByName.values());
    }

    public Set<String> backendNames() {
        return Set.copyOf(clientsByName.keySet());
    }

    /**
     * Resolves which backend owns a tool, by name. Falls back to {@code "unknown"} for a tool
     * not seen during {@link #connect()} (e.g. a backend that registered tools dynamically after
     * startup) so callers always have a label for metrics/audit.
     */
    public String backendOf(String toolName) {
        return toolToBackend.getOrDefault(toolName, "unknown");
    }

    public Map<String, List<String>> toolCatalog() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        synchronized (toolToBackend) {
            toolToBackend.forEach((tool, backend) -> catalog
                    .computeIfAbsent(backend, k -> new java.util.ArrayList<>())
                    .add(tool));
        }
        return catalog;
    }

    public Map<String, String> health() {
        Map<String, String> details = new LinkedHashMap<>();
        clientsByName.forEach((name, client) -> {
            try {
                client.ping();
                details.put(name, "UP");
            } catch (Exception ex) {
                details.put(name, "DOWN: " + ex.getMessage());
            }
        });
        return details;
    }

    private static String clientName(McpSyncClient client) {
        try {
            return client.getClientInfo().name();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
