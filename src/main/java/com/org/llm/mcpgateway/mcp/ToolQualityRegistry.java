package com.org.llm.mcpgateway.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tool quality signal — call count, success rate and p95 latency — the same kind of
 * "is this tool reliable" data Uber's Agent Builder leans on when surfacing/ranking tools for
 * no-code agent construction. Every invocation already flows through
 * {@link GatewayToolCallbackProvider}'s resilience wrapper, so this just taps that same call
 * site instead of adding a new one.
 *
 * <p>Each tool also gets a Micrometer {@link Timer} (tagged tool/backend/outcome) for free
 * Prometheus/Grafana dashboards; the in-memory {@link ToolStats} below is a separate, simpler
 * structure purely so {@code GET /gateway/tools/quality} can answer instantly without querying
 * a metrics backend.
 */
@Component
@RequiredArgsConstructor
public class ToolQualityRegistry {

    private static final int MAX_LATENCY_SAMPLES = 200;

    private final MeterRegistry meterRegistry;
    private final Map<String, ToolStats> statsByTool = new ConcurrentHashMap<>();

    /** Records. */
    public void record(String tool, String backend, long durationMs, boolean success) {
        statsByTool.computeIfAbsent(tool, t -> new ToolStats(backend)).record(durationMs, success);
        meterRegistry.timer("gateway.tool.calls",
                        "tool", tool, "backend", backend, "outcome", success ? "success" : "failure")
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Returns the snapshot. */
    public List<ToolQualityStats> snapshot() {
        return statsByTool.entrySet().stream()
                .map(e -> e.getValue().toStats(e.getKey()))
                .sorted(Comparator.comparing(ToolQualityStats::tool))
                .toList();
    }

    private static final class ToolStats {

        private final String backend;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong successes = new AtomicLong();
        private final Deque<Long> recentLatenciesMs = new ArrayDeque<>(MAX_LATENCY_SAMPLES);

        ToolStats(String backend) {
            this.backend = backend;
        }

        synchronized void record(long durationMs, boolean success) {
            calls.incrementAndGet();
            if (success) {
                successes.incrementAndGet();
            }
            recentLatenciesMs.addLast(durationMs);
            if (recentLatenciesMs.size() > MAX_LATENCY_SAMPLES) {
                recentLatenciesMs.removeFirst();
            }
        }

        synchronized ToolQualityStats toStats(String tool) {
            long callCount = calls.get();
            long successCount = successes.get();
            double successRate = callCount == 0 ? 1.0 : (double) successCount / callCount;
            return new ToolQualityStats(tool, backend, callCount, successCount, successRate, p95());
        }

        private long p95() {
            if (recentLatenciesMs.isEmpty()) {
                return 0;
            }
            List<Long> sorted = recentLatenciesMs.stream().sorted().toList();
            int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }
}
