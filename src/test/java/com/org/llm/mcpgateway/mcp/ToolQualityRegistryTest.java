package com.org.llm.mcpgateway.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolQualityRegistryTest {

    @Test
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
    void computesP95LatencyFromRecentSamples() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        for (long ms = 1; ms <= 20; ms++) {
            registry.record("slowTool", "travel", ms, true);
        }

        ToolQualityStats stats = registry.snapshot().get(0);
        assertThat(stats.p95LatencyMs()).isEqualTo(19);
    }

    @Test
    void toolsWithNoCallsAreNotPresentInSnapshot() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void separateToolsAreTrackedIndependently() {
        ToolQualityRegistry registry = new ToolQualityRegistry(new SimpleMeterRegistry());
        registry.record("toolA", "backendA", 5, true);
        registry.record("toolB", "backendB", 7, false);

        assertThat(registry.snapshot()).hasSize(2)
                .extracting(ToolQualityStats::tool)
                .containsExactly("toolA", "toolB");
    }
}
