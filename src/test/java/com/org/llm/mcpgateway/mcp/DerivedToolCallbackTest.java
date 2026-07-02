package com.org.llm.mcpgateway.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DerivedToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolCallback baseTool() {
        ToolCallback base = mock(ToolCallback.class);
        when(base.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("createIssue")
                .description("Creates a GitHub issue")
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(base.call(anyString())).thenReturn("ok");
        return base;
    }

    @Test
    void exposesDerivedNameAndDescriptionWithBaseSchema() {
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "Creates a bug-labelled issue", baseTool(), Map.of(), objectMapper);

        ToolDefinition definition = derived.getToolDefinition();

        assertThat(definition.name()).isEqualTo("createBugIssue");
        assertThat(definition.description()).isEqualTo("Creates a bug-labelled issue");
        assertThat(definition.inputSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void blankDescriptionFallsBackToBaseDescription() {
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", " ", baseTool(), Map.of(), objectMapper);

        assertThat(derived.getToolDefinition().description()).isEqualTo("Creates a GitHub issue");
    }

    @Test
    void mergesFixedArgumentsIntoCall() {
        ToolCallback base = baseTool();
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "d", base, Map.of("labels", "bug"), objectMapper);

        derived.call("{\"title\":\"login broken\"}");

        verify(base).call("{\"title\":\"login broken\",\"labels\":\"bug\"}");
    }

    @Test
    void fixedArgumentWinsOverCallerValue() {
        ToolCallback base = baseTool();
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "d", base, Map.of("labels", "bug"), objectMapper);

        derived.call("{\"labels\":\"feature\"}");

        verify(base).call("{\"labels\":\"bug\"}");
    }

    @Test
    void emptyInputBecomesObjectWithFixedArguments() {
        ToolCallback base = baseTool();
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "d", base, Map.of("labels", "bug"), objectMapper);

        derived.call("");

        verify(base).call("{\"labels\":\"bug\"}");
    }

    @Test
    void nonObjectInputForwardedUnchanged() {
        ToolCallback base = baseTool();
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "d", base, Map.of("labels", "bug"), objectMapper);

        derived.call("[1,2,3]");

        verify(base).call("[1,2,3]");
    }

    @Test
    void noFixedArgumentsForwardsInputUntouched() {
        ToolCallback base = baseTool();
        DerivedToolCallback derived = new DerivedToolCallback(
                "createBugIssue", "d", base, Map.of(), objectMapper);

        derived.call("{\"title\":\"x\"}");

        verify(base).call("{\"title\":\"x\"}");
    }
}
