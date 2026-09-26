package com.org.llm.mcpgateway.web;

import java.util.concurrent.Callable;

/**
 * Per-request context propagated on the request thread. Holds the acting user — resolved from
 * the inbound JWT when OAuth2 is enabled, otherwise from {@code X-Acting-User} — so it can be
 * forwarded to downstream MCP servers, and the correlation id for log/trace stitching.
 */
public final class RequestContext {

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    /** Handles set. */
    public static void set(String user, String correlationId) {
        HOLDER.set(new Ctx(user, correlationId));
    }

    /** Returns the user. */
    public static String user() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.user();
    }

    /** Returns the correlation id. */
    public static String correlationId() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.correlationId();
    }

    /** Clears. */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * Wraps {@code task} so it runs with the calling thread's context. A thread-local is not
     * inherited by the worker thread a tool call runs on (for its timeout), so without this the
     * downstream call went out with no {@code X-Acting-User} or {@code X-Request-ID}.
     */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Ctx captured = HOLDER.get();
        return () -> {
            Ctx previous = HOLDER.get();
            if (captured == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(captured);
            }
            try {
                return task.call();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }

    private record Ctx(String user, String correlationId) {
    }
}
