package com.org.llm.mcpgateway.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    @DisplayName("Redacts an email address found in the text")
    void redactsEmailAddress() {
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());
        PiiRedactor.Result result = redactor.redact("reach me at jane.doe@example.com please");

        assertThat(result.redacted()).isTrue();
        assertThat(result.text()).isEqualTo("reach me at [EMAIL] please");
        assertThat(result.types()).containsExactly("[EMAIL]");
    }

    @Test
    @DisplayName("Redacts multiple PII types (SSN and email) present in the same text")
    void redactsMultipleDetectorTypesInOneText() {
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());
        PiiRedactor.Result result = redactor.redact("ssn 123-45-6789 email jane@example.com");

        assertThat(result.redacted()).isTrue();
        assertThat(result.text()).isEqualTo("ssn [SSN] email [EMAIL]");
        assertThat(result.types()).contains("[SSN]", "[EMAIL]");
    }

    @Test
    @DisplayName("Leaves text with no PII unchanged")
    void leavesCleanTextUnchanged() {
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());
        PiiRedactor.Result result = redactor.redact("no sensitive data here");

        assertThat(result.redacted()).isFalse();
        assertThat(result.text()).isEqualTo("no sensitive data here");
        assertThat(result.types()).isEmpty();
    }

    @Test
    @DisplayName("Returns text unchanged when the redactor is disabled")
    void disabledRedactorReturnsTextUnchanged() {
        PiiRedactionProperties properties = new PiiRedactionProperties();
        properties.setEnabled(false);
        PiiRedactor redactor = new PiiRedactor(properties);

        PiiRedactor.Result result = redactor.redact("jane.doe@example.com");

        assertThat(result.redacted()).isFalse();
        assertThat(result.text()).isEqualTo("jane.doe@example.com");
    }

    @Test
    @DisplayName("Does not falsely redact a bare digit run as a phone number")
    void bareDigitRunIsNotFalselyRedactedAsPhoneNumber() {
        // Regression: the phone pattern's separators must be mandatory, not optional, or this
        // matches arbitrary 9-13 digit runs (ticket IDs, hashes) commonly seen in tool results.
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());
        PiiRedactor.Result result = redactor.redact("01234567890123456789");

        assertThat(result.redacted()).isFalse();
        assertThat(result.text()).isEqualTo("01234567890123456789");
    }

    @Test
    @DisplayName("Redacts a formatted phone number")
    void formattedPhoneNumberIsRedacted() {
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());
        PiiRedactor.Result result = redactor.redact("call me at 555-123-4567 today");

        assertThat(result.redacted()).isTrue();
        assertThat(result.text()).isEqualTo("call me at [PHONE] today");
    }

    @Test
    @DisplayName("Returns null and blank text unchanged without redaction")
    void nullAndBlankTextAreReturnedUnchanged() {
        PiiRedactor redactor = new PiiRedactor(new PiiRedactionProperties());

        assertThat(redactor.redact(null).text()).isNull();
        assertThat(redactor.redact("").redacted()).isFalse();
    }
}
