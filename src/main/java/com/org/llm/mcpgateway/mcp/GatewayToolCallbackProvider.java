package com.org.llm.mcpgateway.mcp;

import com.org.llm.mcpgateway.config.DerivedTool;
import com.org.llm.mcpgateway.config.GatewayProperties;
import com.org.llm.mcpgateway.config.ToolOverride;
import com.org.llm.mcpgateway.exception.BackendUnavailableException;
import com.org.llm.mcpgateway.exception.GatewayException;
import com.org.llm.mcpgateway.guardrail.PiiRedactor;
import com.org.llm.mcpgateway.guardrail.PromptInjectionGuard;
import com.org.llm.mcpgateway.web.GatewayRateLimiter;
import com.org.llm.mcpgateway.web.RequestContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The gateway's own exposed tool catalog: every tool discovered on every reachable backend
 * (via {@link BackendRegistry}), each wrapped with per-backend circuit breaker + retry, a
 * hard timeout, per-user write-tool rate limiting, audit logging, PII redaction and
 * output-size capping — plus operator-defined description overrides and derived tools layered
 * on top. Picked up automatically by {@code spring-ai-starter-mcp-server-webmvc} as this
 * gateway's own MCP Streamable HTTP tool set — this is what turns the gateway from "a client of
 * N servers" into "a server that fronts N servers" for upstream callers.
 */
@Slf4j
@Primary
@Component
public class GatewayToolCallbackProvider implements ToolCallbackProvider {

    private final AtomicReference<ToolCallback[]> toolCallbackCache = new AtomicReference<>();

    private final BackendRegistry backendRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final GatewayProperties properties;
    private final ToolAuditLog auditLog;
    private final ToolQualityRegistry toolQualityRegistry;
    private final PiiRedactor piiRedactor;
    private final PromptInjectionGuard injectionGuard;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<GatewayRateLimiter> rateLimiterProvider;

    public GatewayToolCallbackProvider(BackendRegistry backendRegistry,
                                        CircuitBreakerRegistry circuitBreakerRegistry,
                                        RetryRegistry retryRegistry,
                                        GatewayProperties properties,
                                        ToolAuditLog auditLog,
                                        ToolQualityRegistry toolQualityRegistry,
                                        PiiRedactor piiRedactor,
                                        PromptInjectionGuard injectionGuard,
                                        ObjectMapper objectMapper,
                                        ObjectProvider<GatewayRateLimiter> rateLimiterProvider) {
        this.backendRegistry = backendRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.properties = properties;
        this.auditLog = auditLog;
        this.toolQualityRegistry = toolQualityRegistry;
        this.piiRedactor = piiRedactor;
        this.injectionGuard = injectionGuard;
        this.objectMapper = objectMapper;
        this.rateLimiterProvider = rateLimiterProvider;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] cached = toolCallbackCache.get();
        if (cached != null) return cached;
        ToolCallback[] fresh = buildToolCallbacks();
        toolCallbackCache.set(fresh);
        return fresh;
    }

    /**
     * Invalidates the cached tool catalogue. Call this when a backend reconnects or when
     * the operator posts to {@code POST /gateway/tools/reload}.
     */
    public void invalidateCache() {
        toolCallbackCache.set(null);
    }

    private ToolCallback[] buildToolCallbacks() {
        SyncMcpToolCallbackProvider delegate = SyncMcpToolCallbackProvider.builder()
                .mcpClients(backendRegistry.availableClients())
                .build();

        Map<String, ToolCallback> wrapped = new LinkedHashMap<>();
        for (ToolCallback callback : delegate.getToolCallbacks()) {
            ToolCallback resilient = applyOverride(wrap(callback));
            wrapped.put(resilient.getToolDefinition().name(), resilient);
        }

        return Stream.concat(wrapped.values().stream(), buildDerivedTools(wrapped).stream())
                .toArray(ToolCallback[]::new);
    }

    ToolCallback wrap(ToolCallback callback) {
        String toolName = callback.getToolDefinition().name();
        String backend = backendRegistry.backendOf(toolName);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(backend);
        Retry retry = retryRegistry.retry(backend);
        boolean write = isWriteTool(toolName);
        return new ResilientToolCallback(callback, circuitBreaker, retry, backend, write);
    }

    ToolCallback applyOverride(ToolCallback callback) {
        ToolOverride override = properties.getToolOverrides().get(callback.getToolDefinition().name());
        if (override == null || override.getDescription() == null || override.getDescription().isBlank()) {
            return callback;
        }
        return new DescriptionOverrideToolCallback(callback, override.getDescription());
    }

    List<ToolCallback> buildDerivedTools(Map<String, ToolCallback> wrapped) {
        List<ToolCallback> derived = new ArrayList<>();
        properties.getDerivedTools().forEach((newName, config) -> {
            ToolCallback base = wrapped.get(config.getBaseTool());
            if (base == null) {
                log.warn("Derived tool '{}' references unknown or unreachable base tool '{}', skipping",
                        newName, config.getBaseTool());
                return;
            }
            derived.add(new DerivedToolCallback(newName, config.getDescription(), base,
                    config.getFixedArguments(), objectMapper));
        });
        return derived;
    }

    private boolean isWriteTool(String toolName) {
        String lower = toolName.toLowerCase(Locale.ROOT);
        return properties.getWriteToolKeywords().stream().anyMatch(lower::contains);
    }

    private final class ResilientToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final CircuitBreaker circuitBreaker;
        private final Retry retry;
        private final String backend;
        private final boolean write;

        ResilientToolCallback(ToolCallback delegate, CircuitBreaker circuitBreaker, Retry retry,
                               String backend, boolean write) {
            this.delegate = delegate;
            this.circuitBreaker = circuitBreaker;
            this.retry = retry;
            this.backend = backend;
            this.write = write;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            if (!injectionGuard.isInputSafe(toolInput, getToolDefinition().name())) {
                return injectionGuard.blockResponse();
            }
            return execute(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (!injectionGuard.isInputSafe(toolInput, getToolDefinition().name())) {
                return injectionGuard.blockResponse();
            }
            return execute(() -> delegate.call(toolInput, toolContext));
        }

        private String execute(Callable<String> action) {
            String toolName = delegate.getToolDefinition().name();
            String user = RequestContext.user();

            if (write) {
                GatewayRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
                if (rateLimiter != null
                        && !rateLimiter.tryAcquire("write:" + user, properties.getWriteRateLimitPerMinute())) {
                    log.warn("Write-tool rate limit exceeded | user={} tool={}", user, toolName);
                    return "{\"error\":\"Write-tool rate limit exceeded. Maximum "
                            + properties.getWriteRateLimitPerMinute() + " writes per minute.\"}";
                }
            }

            Callable<String> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, action);
            Callable<String> withRetryAndCircuitBreaker = Retry.decorateCallable(retry, withCircuitBreaker);

            long start = System.currentTimeMillis();
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<String> future = executor.submit(withRetryAndCircuitBreaker);
            try {
                String rawResult = future.get(properties.getToolTimeoutSeconds(), TimeUnit.SECONDS);
                long durationMs = System.currentTimeMillis() - start;
                recordOutcome(toolName, user, durationMs, true);
                return sanitizeOutput(toolName, rawResult);
            } catch (TimeoutException e) {
                future.cancel(true);
                recordOutcome(toolName, user, System.currentTimeMillis() - start, false);
                log.warn("Tool call timed out after {}s | backend={} tool={}",
                        properties.getToolTimeoutSeconds(), backend, toolName);
                return "{\"error\":\"Tool call timed out after " + properties.getToolTimeoutSeconds() + "s\"}";
            } catch (ExecutionException e) {
                recordOutcome(toolName, user, System.currentTimeMillis() - start, false);
                Throwable cause = e.getCause();
                if (cause instanceof CallNotPermittedException) {
                    log.warn("Circuit breaker OPEN for backend {} — surfacing as BackendUnavailableException", backend);
                    throw new BackendUnavailableException(
                            "Backend '" + backend + "' is temporarily unavailable (circuit open). Please try again later.",
                            cause);
                }
                log.error("Tool call failed | backend={} tool={}: {}", backend, toolName,
                        cause != null ? cause.getMessage() : e.getMessage());
                throw new GatewayException("MCP tool call failed: "
                        + (cause != null ? cause.getMessage() : e.getMessage()), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"error\":\"Tool call interrupted\"}";
            } finally {
                executor.shutdown();
            }
        }

        private void recordOutcome(String toolName, String user, long durationMs, boolean success) {
            auditLog.logInvocation(user, backend, toolName, durationMs, success);
            toolQualityRegistry.record(toolName, backend, durationMs, success);
        }

        private String sanitizeOutput(String toolName, String result) {
            PiiRedactor.Result piiResult = piiRedactor.redact(result);
            if (piiResult.redacted()) {
                log.info("PII redacted from tool result | tool={} backend={} types={}",
                        toolName, backend, piiResult.types());
            }

            String text = piiResult.text();
            String capped = OutputSizeCapUtil.cap(text, properties.getMaxToolResultChars());
            if (capped != null && text != null && capped.length() < text.length()) {
                auditLog.logTruncation(toolName, text.length(), properties.getMaxToolResultChars());
            }
            return capped;
        }
    }
}
