package com.org.llm.mcpgateway.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSizeCapUtilTest {

    @Test
    void returnsNullUnchanged() {
        assertThat(OutputSizeCapUtil.cap(null, 10)).isNull();
    }

    @Test
    void returnsTextUnderLimitUnchanged() {
        assertThat(OutputSizeCapUtil.cap("short", 10)).isEqualTo("short");
    }

    @Test
    void returnsTextExactlyAtLimitUnchanged() {
        assertThat(OutputSizeCapUtil.cap("1234567890", 10)).isEqualTo("1234567890");
    }

    @Test
    void truncatesOversizedTextAndAppendsSuffix() {
        String result = OutputSizeCapUtil.cap("a".repeat(100), 50);

        assertThat(result).hasSize(50);
        assertThat(result).endsWith("…[truncated]");
    }

    @Test
    void capSmallerThanSuffixStillProducesSuffixOnly() {
        String result = OutputSizeCapUtil.cap("abcdefghij", 5);

        assertThat(result).isEqualTo("…[truncated]");
    }
}
