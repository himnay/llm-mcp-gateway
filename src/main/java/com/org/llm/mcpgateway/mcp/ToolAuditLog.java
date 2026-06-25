package com.org.llm.mcpgateway.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ToolAuditLog {

    public void logInvocation(String user, String backend, String tool, long durationMs, boolean success) {
        log.info("TOOL_AUDIT user={} backend={} tool={} durationMs={} success={}",
                user, backend, tool, durationMs, success);
    }

    public void logTruncation(String tool, int originalSize, int maxSize) {
        log.warn("TOOL_TRUNCATION tool={} originalSize={} maxSize={}", tool, originalSize, maxSize);
    }
}
