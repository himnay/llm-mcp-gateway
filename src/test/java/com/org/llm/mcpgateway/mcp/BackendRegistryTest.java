package com.org.llm.mcpgateway.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendRegistryTest {

    private static McpSyncClient mockClient(String name, String... toolNames) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation(name, "1.0.0"));
        List<McpSchema.Tool> tools = List.of(toolNames).stream()
                .map(t -> McpSchema.Tool.builder(t).description(t).build())
                .toList();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));
        return client;
    }

    private static ObjectProvider<List<McpSyncClient>> providerOf(List<McpSyncClient> clients) {
        @SuppressWarnings("unchecked")
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(java.util.stream.Stream.of(clients));
        return provider;
    }

    @Test
    @DisplayName("Builds the tool-to-backend map from each connected client's own tool catalog")
    void buildsToolToBackendMapFromEachClientsOwnCatalog() {
        McpSyncClient github = mockClient("github", "getRepository", "createIssue");
        McpSyncClient hr = mockClient("hr", "applyLeave");

        BackendRegistry registry = new BackendRegistry(providerOf(List.of(github, hr)));
        registry.connect();

        assertThat(registry.backendNames()).containsExactlyInAnyOrder("github", "hr");
        assertThat(registry.backendOf("getRepository")).isEqualTo("github");
        assertThat(registry.backendOf("createIssue")).isEqualTo("github");
        assertThat(registry.backendOf("applyLeave")).isEqualTo("hr");
        assertThat(registry.toolCatalog())
                .containsEntry("github", List.of("getRepository", "createIssue"))
                .containsEntry("hr", List.of("applyLeave"));
    }

    @Test
    @DisplayName("Falls back to the 'unknown' backend for a tool that was never registered")
    void unknownToolFallsBackToUnknownBackend() {
        BackendRegistry registry = new BackendRegistry(providerOf(List.of()));
        registry.connect();

        assertThat(registry.backendOf("neverSeenTool")).isEqualTo("unknown");
        assertThat(registry.availableClients()).isEmpty();
    }

    @Test
    @DisplayName("Skips an unreachable backend during connect without failing the whole registry")
    void unreachableBackendIsSkippedNotFatal() {
        McpSyncClient broken = mock(McpSyncClient.class);
        when(broken.getClientInfo()).thenReturn(new McpSchema.Implementation("travel", "1.0.0"));
        when(broken.initialize()).thenThrow(new RuntimeException("connection refused"));

        McpSyncClient healthy = mockClient("hr", "applyLeave");

        BackendRegistry registry = new BackendRegistry(providerOf(List.of(broken, healthy)));
        registry.connect();

        assertThat(registry.backendNames()).containsExactly("hr");
        assertThat(registry.health()).containsEntry("hr", "UP");
    }

    @Test
    @DisplayName("Reports a backend as DOWN when its health ping fails")
    void healthPingFailureIsReportedDown() {
        McpSyncClient flaky = mockClient("notification", "sendNotification");
        when(flaky.ping()).thenThrow(new RuntimeException("timeout"));

        BackendRegistry registry = new BackendRegistry(providerOf(List.of(flaky)));
        registry.connect();

        Map<String, String> health = registry.health();
        assertThat(health.get("notification")).startsWith("DOWN");
    }
}
