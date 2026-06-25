package com.org.llm.mcpgateway.admin;

import java.util.List;

public record BackendStatus(String name, String status, int toolCount, List<String> tools) {
}
