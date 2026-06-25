package com.org.llm.mcpgateway.web;

/**
 * Per-request context propagated on the request thread. Holds the acting user — resolved from
 * the inbound JWT when OAuth2 is enabled, otherwise from {@code X-Acting-User} — so it can be
 * forwarded to downstream MCP servers, and the correlation id for log/trace stitching.
 */
public final class RequestContext {

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String user, String correlationId) {
        HOLDER.set(new Ctx(user, correlationId));
    }

    public static String user() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.user();
    }

    public static String correlationId() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.correlationId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    private record Ctx(String user, String correlationId) {
    }
}
