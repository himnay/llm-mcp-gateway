package com.org.llm.mcpgateway.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Guards incoming MCP tool call arguments against prompt injection.
 *
 * <p>MCP tool arguments can carry injected instructions that attempt to hijack the upstream LLM
 * (indirect injection via tool inputs). This guard checks every tool call input string before
 * dispatching to the backend MCP server — if injection is detected, the call is rejected
 * with an error JSON response rather than forwarded.</p>
 *
 * <p>Patterns are externalised in {@link InjectionGuardProperties}
 * ({@code app.security.injection-guard.patterns}) so new attack signatures can be added
 * in configuration without code changes.</p>
 */
@Slf4j
@Component
public class PromptInjectionGuard {

    private final List<Pattern> compiledPatterns;
    private final boolean enabled;
    private final String blockMessage;

    public PromptInjectionGuard(InjectionGuardProperties properties) {
        this.enabled = properties.isEnabled();
        this.blockMessage = properties.getBlockMessage();
        this.compiledPatterns = properties.getPatterns().stream()
                .flatMap(regex -> {
                    try {
                        return java.util.stream.Stream.of(Pattern.compile(regex));
                    } catch (PatternSyntaxException ex) {
                        log.error("SECURITY | invalid injection pattern skipped | regex='{}' | error={}", regex, ex.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
        log.info("SECURITY | PromptInjectionGuard ready | patterns={} enabled={}", compiledPatterns.size(), enabled);
    }

    /**
     * Returns {@code true} if the text matches none of the configured injection patterns.
     */
    public boolean isSafe(String text) {
        if (!enabled || text == null || text.isBlank()) return true;
        return compiledPatterns.stream().noneMatch(p -> p.matcher(text).find());
    }

    /**
     * Validates a tool call input string. Returns {@code false} if injection is detected.
     */
    public boolean isInputSafe(String toolInput, String toolName) {
        boolean safe = isSafe(toolInput);
        if (!safe) {
            log.warn("SECURITY | Injection pattern in tool input | tool={}", toolName);
        }
        return safe;
    }

    /** JSON error response to return when a tool call is blocked. */
    public String blockResponse() {
        return "{\"error\":\"" + blockMessage + "\"}";
    }
}
