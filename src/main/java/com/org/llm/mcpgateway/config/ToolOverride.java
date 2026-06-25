package com.org.llm.mcpgateway.config;

import lombok.Getter;
import lombok.Setter;

/**
 * <pre>
 * gateway:
 *   tool-overrides:
 *     createIssue:
 *       description: "Create a GitHub issue. Only use for confirmed bugs, not feature requests."
 * </pre>
 */
@Getter
@Setter
public class ToolOverride {

    private String description;
}
