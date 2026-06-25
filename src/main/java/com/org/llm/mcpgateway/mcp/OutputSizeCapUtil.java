package com.org.llm.mcpgateway.mcp;

final class OutputSizeCapUtil {

    private static final String TRUNCATION_SUFFIX = "…[truncated]";

    private OutputSizeCapUtil() {
    }

    static String cap(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - TRUNCATION_SUFFIX.length())) + TRUNCATION_SUFFIX;
    }
}
