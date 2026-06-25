package com.org.llm.mcpgateway.mcp;

public record ToolQualityStats(String tool, String backend, long callCount, long successCount,
                                double successRate, long p95LatencyMs) {
}
