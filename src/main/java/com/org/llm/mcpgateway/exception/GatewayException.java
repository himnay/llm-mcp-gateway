package com.org.llm.mcpgateway.exception;

/**
 * Internal gateway failure (e.g. Keycloak token fetch failed) — mapped to HTTP 500 by
 * {@link com.org.llm.mcpgateway.web.GlobalExceptionHandler}.
 */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
