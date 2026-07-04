package com.org.llm.mcpgateway.mcp;

import com.org.llm.mcpgateway.config.GatewayProperties;
import com.org.llm.mcpgateway.exception.BackendUnavailableException;
import com.org.llm.mcpgateway.exception.GatewayException;
import com.org.llm.mcpgateway.guardrail.InjectionGuardProperties;
import com.org.llm.mcpgateway.guardrail.PiiRedactionProperties;
import com.org.llm.mcpgateway.guardrail.PiiRedactor;
import com.org.llm.mcpgateway.guardrail.PromptInjectionGuard;
import com.org.llm.mcpgateway.web.GatewayRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayToolCallbackProviderTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final ToolQualityRegistry toolQualityRegistry = new ToolQualityRegistry(new SimpleMeterRegistry());
    private final PiiRedactor piiRedactor = new PiiRedactor(new PiiRedactionProperties());
    private final PromptInjectionGuard injectionGuard = new PromptInjectionGuard(new InjectionGuardProperties());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GatewayToolCallbackProvider newProvider(CircuitBreakerRegistry cbRegistry, RetryRegistry retryRegistry,
                                                      GatewayRateLimiter rateLimiter) {
        BackendRegistry backendRegistry = mock(BackendRegistry.class);
        when(backendRegistry.backendOf("createIssue")).thenReturn("github");
        when(backendRegistry.backendOf("getRepository")).thenReturn("github");

        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayRateLimiter> rateLimiterProvider = mock(ObjectProvider.class);
        when(rateLimiterProvider.getIfAvailable()).thenReturn(rateLimiter);

        return new GatewayToolCallbackProvider(backendRegistry, cbRegistry, retryRegistry, properties,
                new ToolAuditLog(), toolQualityRegistry, piiRedactor, injectionGuard, objectMapper, rateLimiterProvider);
    }

    private static ToolCallback fakeCallback(String name, java.util.function.Supplier<String> action) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return action.get();
            }
        };
    }

    private static CircuitBreakerRegistry freshCircuitBreakerRegistry() {
        // minimumNumberOfCalls(4) so a single transient failure in the retry test below never
        // trips the breaker open before the retry's second attempt gets a chance to succeed.
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
    }

    private static RetryRegistry noRetryRegistry() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    @Test
    @DisplayName("Returns a successful tool call result unchanged when it is under the size cap")
    void successfulCallUnderTheCapIsReturnedUnchanged() {
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);

        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> "small result"));

        assertThat(wrapped.call("{}")).isEqualTo("small result");
    }

    @Test
    @DisplayName("Truncates a successful tool call result that exceeds the size cap")
    void successfulCallOverTheCapIsTruncated() {
        properties.setMaxToolResultChars(15);
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);

        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> "01234567890123456789"));

        assertThat(wrapped.call("{}")).isEqualTo("012…[truncated]").hasSize(15);
    }

    @Test
    @DisplayName("Blocks a write tool call when the rate limiter denies the request")
    void writeToolIsBlockedWhenRateLimiterDenies() {
        GatewayRateLimiter rateLimiter = mock(GatewayRateLimiter.class);
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.startsWith("write:"),
                org.mockito.ArgumentMatchers.eq(properties.getWriteRateLimitPerMinute())))
                .thenReturn(false);

        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), rateLimiter);
        ToolCallback wrapped = provider.wrap(fakeCallback("createIssue", () -> "should-not-run"));

        assertThat(wrapped.call("{}")).contains("Write-tool rate limit exceeded");
    }

    @Test
    @DisplayName("Surfaces an open circuit breaker as a BackendUnavailableException")
    void openCircuitBreakerSurfacesAsBackendUnavailable() {
        CircuitBreakerRegistry cbRegistry = freshCircuitBreakerRegistry();
        // force the "github" breaker open before any call
        CircuitBreaker breaker = cbRegistry.circuitBreaker("github");
        breaker.transitionToOpenState();

        GatewayToolCallbackProvider provider = newProvider(cbRegistry, noRetryRegistry(), null);
        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> "unreachable"));

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(BackendUnavailableException.class)
                .hasMessageContaining("github");
    }

    @Test
    @DisplayName("Propagates a generic tool failure as a GatewayException")
    void genericFailurePropagatesAsGatewayException() {
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> {
            throw new IllegalStateException("boom");
        }));

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("Retries a transient failure once before the call succeeds")
    void retryRetriesTransientFailureBeforeSucceeding() {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(RuntimeException.class)
                .build());
        AtomicInteger attempts = new AtomicInteger();

        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), retryRegistry, null);
        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("transient");
            }
            return "ok";
        }));

        assertThat(wrapped.call("{}")).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Redacts PII contained in a tool result before returning it")
    void piiInToolResultIsRedactedBeforeReturning() {
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> "contact jane.doe@example.com for access"));

        assertThat(wrapped.call("{}")).isEqualTo("contact [EMAIL] for access");
    }

    @Test
    @DisplayName("Records a successful call in the tool quality registry")
    void successfulCallIsRecordedInToolQualityRegistry() {
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        ToolCallback wrapped = provider.wrap(fakeCallback("getRepository", () -> "ok"));
        wrapped.call("{}");

        ToolQualityStats stats = toolQualityRegistry.snapshot().stream()
                .filter(s -> s.tool().equals("getRepository"))
                .findFirst().orElseThrow();
        assertThat(stats.backend()).isEqualTo("github");
        assertThat(stats.callCount()).isEqualTo(1);
        assertThat(stats.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Description override replaces only the tool's description, leaving the name unchanged")
    void descriptionOverrideReplacesDescriptionOnly() {
        com.org.llm.mcpgateway.config.ToolOverride override = new com.org.llm.mcpgateway.config.ToolOverride();
        override.setDescription("Only use for confirmed bugs, not feature requests.");
        properties.getToolOverrides().put("createIssue", override);

        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        ToolCallback overridden = provider.applyOverride(
                provider.wrap(fakeCallback("createIssue", () -> "issue created")));

        assertThat(overridden.getToolDefinition().name()).isEqualTo("createIssue");
        assertThat(overridden.getToolDefinition().description())
                .isEqualTo("Only use for confirmed bugs, not feature requests.");
    }

    @Test
    @DisplayName("Builds a derived tool that merges fixed arguments into the base tool's call input")
    void derivedToolMergesFixedArgumentsIntoBaseToolCall() {
        com.org.llm.mcpgateway.config.DerivedTool derived = new com.org.llm.mcpgateway.config.DerivedTool();
        derived.setBaseTool("createIssue");
        derived.setDescription("Create a pre-labelled bug issue");
        derived.getFixedArguments().put("labels", "bug");
        properties.getDerivedTools().put("createBugIssue", derived);

        java.util.concurrent.atomic.AtomicReference<String> seenInput = new java.util.concurrent.atomic.AtomicReference<>();
        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        ToolCallback base = provider.wrap(fakeCallback("createIssue", () -> "issue created"));

        ToolCallback baseRecordingInput = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return base.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                seenInput.set(toolInput);
                return base.call(toolInput);
            }
        };

        java.util.List<ToolCallback> derivedTools = provider.buildDerivedTools(
                java.util.Map.of("createIssue", baseRecordingInput));

        assertThat(derivedTools).hasSize(1);
        ToolCallback derivedCallback = derivedTools.get(0);
        assertThat(derivedCallback.getToolDefinition().name()).isEqualTo("createBugIssue");
        assertThat(derivedCallback.getToolDefinition().description()).isEqualTo("Create a pre-labelled bug issue");

        derivedCallback.call("{\"title\":\"crash on save\"}");

        assertThat(seenInput.get()).contains("\"labels\":\"bug\"").contains("\"title\":\"crash on save\"");
    }

    @Test
    @DisplayName("Skips building a derived tool when its configured base tool does not exist")
    void derivedToolWithUnknownBaseToolIsSkipped() {
        com.org.llm.mcpgateway.config.DerivedTool derived = new com.org.llm.mcpgateway.config.DerivedTool();
        derived.setBaseTool("doesNotExist");
        properties.getDerivedTools().put("orphanDerived", derived);

        GatewayToolCallbackProvider provider = newProvider(freshCircuitBreakerRegistry(), noRetryRegistry(), null);
        assertThat(provider.buildDerivedTools(java.util.Map.of())).isEmpty();
    }
}
