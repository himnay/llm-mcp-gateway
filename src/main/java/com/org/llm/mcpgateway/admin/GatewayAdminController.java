package com.org.llm.mcpgateway.admin;

import com.org.llm.mcpgateway.mcp.BackendRegistry;
import com.org.llm.mcpgateway.mcp.GatewayToolCallbackProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only operational view of the backend fleet this gateway aggregates — the "centralized
 * management" half of the gateway pattern, distinct from the MCP protocol endpoint itself.
 * Protected by the same {@code gateway-invoke} scope as {@code /mcp/**} (see
 * {@link com.org.llm.mcpgateway.security.GatewaySecurityConfig}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/gateway/backends")
public class GatewayAdminController {

    private final BackendRegistry backendRegistry;
    private final GatewayToolCallbackProvider toolCallbackProvider;

    @GetMapping
    public List<BackendStatus> listBackends() {
        Map<String, String> health = backendRegistry.health();
        Map<String, List<String>> catalog = backendRegistry.toolCatalog();

        return backendRegistry.backendNames().stream()
                .map(name -> {
                    List<String> tools = catalog.getOrDefault(name, List.of());
                    return new BackendStatus(name, health.getOrDefault(name, "UNKNOWN"), tools.size(), tools);
                })
                .toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<BackendStatus> getBackend(@PathVariable String name) {
        if (!backendRegistry.backendNames().contains(name)) {
            return ResponseEntity.notFound().build();
        }
        List<String> tools = backendRegistry.toolCatalog().getOrDefault(name, List.of());
        String status = backendRegistry.health().getOrDefault(name, "UNKNOWN");
        return ResponseEntity.ok(new BackendStatus(name, status, tools.size(), tools));
    }
}


