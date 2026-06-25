package com.org.llm.mcpgateway.config;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <pre>
 * gateway:
 *   derived-tools:
 *     createBugIssue:
 *       base-tool: createIssue
 *       description: "Create a GitHub bug-tracker issue, pre-labelled 'bug'."
 *       fixed-arguments:
 *         labels: bug
 * </pre>
 */
@Getter
@Setter
public class DerivedTool {

    private String baseTool;

    private String description;

    private Map<String, Object> fixedArguments = new LinkedHashMap<>();
}
