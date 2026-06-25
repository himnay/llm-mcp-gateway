package com.org.llm.mcpgateway.guardrail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalised PII/secret detection patterns for {@link PiiRedactor} — the type name becomes
 * the redaction placeholder, e.g. {@code email -> [EMAIL]}. Bound from {@code gateway.pii.*};
 * any value present in configuration replaces the corresponding default.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.pii")
public class PiiRedactionProperties {

    private boolean enabled = true;

    private Map<String, String> patterns = defaultPatterns();

    private static Map<String, String> defaultPatterns() {
        Map<String, String> m = new LinkedHashMap<>();
        // Secrets / credentials (most specific first)
        m.put("private-key",
                "-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----");
        m.put("api-key", "\\b(?:sk|sk-ant|sk-proj|rk|pk)-[A-Za-z0-9_\\-]{16,}\\b");
        m.put("aws-key", "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
        m.put("bearer-token", "(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{20,}");
        // PII
        m.put("email", "[\\w.%+\\-]+@[\\w.\\-]+\\.[A-Za-z]{2,}");
        m.put("credit-card",
                "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b");
        m.put("ssn", "\\b\\d{3}[-\\s]\\d{2}[-\\s]\\d{4}\\b");
        m.put("iban", "\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]?){0,16}\\b");
        m.put("ip-address",
                "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
        // Separators between groups are required (not optional) so this doesn't false-positive
        // on bare 9-13 digit runs (ticket IDs, hashes, etc.) commonly seen in tool results.
        m.put("phone", "(?:\\+\\d{1,3}[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]\\d{3}[\\s.\\-]\\d{4}\\b");
        return m;
    }
}
