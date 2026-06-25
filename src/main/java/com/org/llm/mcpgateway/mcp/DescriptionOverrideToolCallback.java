package com.org.llm.mcpgateway.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates a tool callback with an operator-supplied description override (config-driven via
 * {@code gateway.tool-overrides}), leaving name, input schema and execution untouched — agents
 * get clearer, workflow-specific guidance on an existing tool without the backend changing.
 */
final class DescriptionOverrideToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String description;

    DescriptionOverrideToolCallback(ToolCallback delegate, String description) {
        this.delegate = delegate;
        this.description = description;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition base = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(base.name())
                .description(description)
                .inputSchema(base.inputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
