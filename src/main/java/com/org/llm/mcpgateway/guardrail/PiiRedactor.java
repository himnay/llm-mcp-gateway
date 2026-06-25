package com.org.llm.mcpgateway.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Redacts PII/secrets from tool results before they reach the caller — defence-in-depth for
 * backends that don't sanitize their own output, applied by
 * {@link com.org.llm.mcpgateway.mcp.GatewayToolCallbackProvider} right before output capping.
 * Patterns are externalised in {@link PiiRedactionProperties} ({@code gateway.pii.patterns});
 * add or remove detectors purely in configuration.
 *
 * <p><b>NOTE:</b> regex detection has inherent false-positive/negative limits. For regulated
 * workloads, augment with a dedicated service (AWS Comprehend, Azure AI Content Safety, Microsoft
 * Presidio).
 */
@Slf4j
@Component
public class PiiRedactor {

    private final boolean enabled;
    private final Map<String, Pattern> patterns = new LinkedHashMap<>();

    public PiiRedactor(PiiRedactionProperties properties) {
        this.enabled = properties.isEnabled();
        properties.getPatterns().forEach((type, regex) -> {
            try {
                patterns.put(toPlaceholder(type), Pattern.compile(regex));
            } catch (PatternSyntaxException ex) {
                log.error("PII | invalid pattern skipped | type={} | error={}", type, ex.getMessage());
            }
        });
        log.info("PII redactor initialised with {} pattern(s), enabled={}", patterns.size(), enabled);
    }

    private static String toPlaceholder(String type) {
        return "[" + type.trim().toUpperCase(Locale.ROOT).replace('-', '_') + "]";
    }

    /**
     * Replaces every detected PII/secret span with its typed placeholder.
     *
     * @param text raw tool result text; {@code null}/blank is returned unchanged
     * @return the redaction outcome — never {@code null}
     */
    public Result redact(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return new Result(false, text, Set.of());
        }
        String result = text;
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
            String replaced = e.getValue().matcher(result).replaceAll(e.getKey());
            if (!replaced.equals(result)) {
                types.add(e.getKey());
                result = replaced;
            }
        }
        return new Result(!result.equals(text), result, types);
    }

    /**
     * @param redacted whether any replacement was made
     * @param text     the (possibly) redacted text
     * @param types    the set of placeholder types that were applied
     */
    public record Result(boolean redacted, String text, Set<String> types) {
    }
}
