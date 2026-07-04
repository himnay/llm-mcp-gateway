package com.org.llm.mcpgateway.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolQualityRegistryTest {

    @Test
    @DisplayName("Aggregates call count and success rate per tool across multiple recordings")
    void aggregatesCallCountAndSuccessRatePerTool() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());

        registry.record("getRepository", "github", 10, true);
        registry.record("getRepository", "github", 20, true);
        registry.record("getRepository", "github", 30, false);

        ToolQualityStats stats = registry.snapshot().get(0);
        assertThat(stats.tool()).isEqualTo("getRepository");
        assertThat(stats.backend()).isEqualTo("github");
        assertThat(stats.callCount()).isEqualTo(3);
        assertThat(stats.successCount()).isEqualTo(2);
        assertThat(stats.successRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("Computes the p95 latency from recent latency samples")
    void computesP95LatencyFromRecentSamples() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        for (long ms = 1; ms <= 20; ms++) {
            registry.record("slowTool", "travel", ms, true);
        }

        ToolQualityStats stats = registry.snapshot().get(0);
        assertThat(stats.p95LatencyMs()).isEqualTo(19);
    }

    @Test
    @DisplayName("Excludes tools with no recorded calls from the snapshot")
    void toolsWithNoCallsAreNotPresentInSnapshot() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("Tracks separate tools' stats independently in the snapshot")
    void separateToolsAreTrackedIndependently() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        registry.record("toolA", "backendA", 5, true);
        registry.record("toolB", "backendB", 7, false);

        assertThat(registry.snapshot()).hasSize(2)
                .extracting(ToolQualityStats::tool)
                .containsExactly("toolA", "toolB");
    }
}
