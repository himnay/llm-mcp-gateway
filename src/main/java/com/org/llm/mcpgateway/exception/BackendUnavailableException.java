package com.org.llm.mcpgateway.exception;

/**
 * Raised when a tool call targets a backend whose circuit is open or that is otherwise
 * unreachable. Mapped to HTTP 502 by {@link com.org.llm.mcpgateway.web.GlobalExceptionHandler}.
 */
public class BackendUnavailableException extends RuntimeException {

    public BackendUnavailableException(String message) {
        super(message);
    }

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
