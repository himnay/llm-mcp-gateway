package com.org.llm.mcpgateway.admin;

import com.org.llm.mcpgateway.mcp.ToolQualityRegistry;
import com.org.llm.mcpgateway.mcp.ToolQualityStats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only tool-quality signal — call count, success rate, p95 latency per tool — so an
 * agent-builder UI (or an operator) can tell which tools are reliable without scraping
 * Prometheus. Protected by the same {@code gateway-invoke} scope as {@code /mcp/**}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/gateway/tools")
public class GatewayToolQualityController {

    private final ToolQualityRegistry toolQualityRegistry;

    @GetMapping("/quality")
    public List<ToolQualityStats> quality() {
        return toolQualityRegistry.snapshot();
    }
}
