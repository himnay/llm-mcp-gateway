package com.org.llm.mcpgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlAllowlistValidatorTest {

    private final GatewaySsrfProperties properties = new GatewaySsrfProperties();
    private final UrlAllowlistValidator validator = new UrlAllowlistValidator(properties);

    @ParameterizedTest
    @DisplayName("Loopback and private backends are allowed by default (local and Docker setups)")
    @ValueSource(strings = {"http://localhost:8081", "http://127.0.0.1:8081", "http://10.1.2.3:8081",
            "http://172.17.0.1:8085", "https://192.168.1.10/mcp"})
    void allowsLoopbackAndPrivateBackendsByDefault(String url) {
        assertDoesNotThrow(() -> validator.validate(url, "backend"));
    }

    @ParameterizedTest
    @DisplayName("Cloud metadata, wildcard and multicast addresses are always blocked")
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://0.0.0.0:8080",
            "http://224.0.0.1", "http://[fe80::1]:8080"})
    void alwaysBlocksSsrfTargets(String url) {
        assertThrows(SecurityException.class, () -> validator.validate(url, "backend"));
    }

    @ParameterizedTest
    @DisplayName("With block-private-networks on, loopback and private ranges are blocked too")
    @ValueSource(strings = {"http://localhost:8081", "http://10.1.2.3", "http://192.168.1.10",
            "http://[fd00::1]:8080"})
    void blocksPrivateNetworksWhenEnabled(String url) {
        properties.setBlockPrivateNetworks(true);
        assertThrows(SecurityException.class, () -> validator.validate(url, "backend"));
    }

    @Test
    @DisplayName("A public address stays allowed with block-private-networks on")
    void allowsPublicAddressWhenBlockingPrivateNetworks() {
        properties.setBlockPrivateNetworks(true);
        assertDoesNotThrow(() -> validator.validate("https://8.8.8.8/mcp", "backend"));
    }

    @ParameterizedTest
    @DisplayName("Blank, schemeless, non-HTTP and hostless URLs are rejected as malformed")
    @ValueSource(strings = {" ", "localhost:8081", "ftp://localhost/file", "file:///etc/passwd", "http:///mcp"})
    void rejectsMalformedUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(url, "backend"));
    }
}
