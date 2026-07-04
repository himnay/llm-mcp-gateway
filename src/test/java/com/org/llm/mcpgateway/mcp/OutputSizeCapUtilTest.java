package com.org.llm.mcpgateway.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSizeCapUtilTest {

    @Test
    @DisplayName("Returns null unchanged when input text is null")
    void returnsNullUnchanged() {
        assertThat(OutputSizeCapUtil.cap(null, 10)).isNull();
    }

    @Test
    @DisplayName("Returns text unchanged when it is under the size limit")
    void returnsTextUnderLimitUnchanged() {
        assertThat(OutputSizeCapUtil.cap("short", 10)).isEqualTo("short");
    }

    @Test
    @DisplayName("Returns text unchanged when it is exactly at the size limit")
    void returnsTextExactlyAtLimitUnchanged() {
        assertThat(OutputSizeCapUtil.cap("1234567890", 10)).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("Truncates oversized text and appends a truncation suffix")
    void truncatesOversizedTextAndAppendsSuffix() {
        String result = OutputSizeCapUtil.cap("a".repeat(100), 50);

        assertThat(result).hasSize(50);
        assertThat(result).endsWith("…[truncated]");
    }

    @Test
    @DisplayName("Produces only the truncation suffix when the cap is smaller than the suffix itself")
    void capSmallerThanSuffixStillProducesSuffixOnly() {
        String result = OutputSizeCapUtil.cap("abcdefghij", 5);

        assertThat(result).isEqualTo("…[truncated]");
    }
}
