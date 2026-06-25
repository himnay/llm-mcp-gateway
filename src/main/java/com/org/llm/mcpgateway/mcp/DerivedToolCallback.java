package com.org.llm.mcpgateway.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * A new, named tool that wraps an existing one with a refined description and a fixed set of
 * arguments merged into every call (config-driven via {@code gateway.derived-tools}) — the
 * "derived tool" pattern: agents get a narrower, more specific entry point onto a
 * general-purpose backend tool (e.g. {@code createBugIssue} fixing {@code labels: "bug"} on
 * top of {@code createIssue}) without the backend itself changing.
 *
 * <p>The input schema is inherited as-is from the base tool — fixed arguments are merged in
 * server-side, so a caller may still pass them, but the gateway's value always wins.
 */
@Slf4j
final class DerivedToolCallback implements ToolCallback {

    private final String name;
    private final String description;
    private final ToolCallback baseTool;
    private final Map<String, Object> fixedArguments;
    private final ObjectMapper objectMapper;

    DerivedToolCallback(String name, String description, ToolCallback baseTool,
                         Map<String, Object> fixedArguments, ObjectMapper objectMapper) {
        this.name = name;
        this.description = description;
        this.baseTool = baseTool;
        this.fixedArguments = fixedArguments;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition base = baseTool.getToolDefinition();
        return ToolDefinition.builder()
                .name(name)
                .description(description != null && !description.isBlank() ? description : base.description())
                .inputSchema(base.inputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return baseTool.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return baseTool.call(mergeFixedArguments(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return baseTool.call(mergeFixedArguments(toolInput), toolContext);
    }

    private String mergeFixedArguments(String toolInput) {
        if (fixedArguments.isEmpty()) {
            return toolInput;
        }
        try {
            JsonNode parsed = (toolInput == null || toolInput.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(toolInput);
            if (!(parsed instanceof ObjectNode node)) {
                log.warn("Derived tool '{}' input was not a JSON object, forwarding unchanged", name);
                return toolInput;
            }
            fixedArguments.forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Failed to merge fixed arguments into derived tool '{}', forwarding input unchanged: {}",
                    name, e.getMessage());
            return toolInput;
        }
    }
}
