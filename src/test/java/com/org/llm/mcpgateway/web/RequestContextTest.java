package com.org.llm.mcpgateway.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextTest {

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("propagate() carries user and correlation id onto the tool-call worker thread")
    void propagatesToWorkerThread() throws Exception {
        RequestContext.set("jane.doe", "req-42");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String seen = executor.submit(RequestContext.propagate(
                    () -> RequestContext.user() + "/" + RequestContext.correlationId())).get();
            assertThat(seen).isEqualTo("jane.doe/req-42");
            assertThat(executor.submit(RequestContext::user).get()).isNull();
        }
    }
}
