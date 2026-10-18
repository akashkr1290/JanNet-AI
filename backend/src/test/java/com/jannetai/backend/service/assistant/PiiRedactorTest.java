package com.jannetai.backend.service.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** JanNet Sahayak: personal data never reaches assistant_messages or ai-service. */
class PiiRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "call me on 9876543210 please|call me on [removed] please",
            "+91 98765 43210|[removed]",
            "my mail is asha.rao@example.com|my mail is [removed]",
            "aadhaar 1234 5678 9012|aadhaar [removed]",
            "house no 12/4 near the temple|[removed] near the temple",
            "मकान नंबर 45 के पास गड्ढा|[removed] के पास गड्ढा",
            "my name is Ravi Kumar and there is garbage|my name is [removed] and there is garbage",
            "mera naam Sunita hai|mera naam [removed] hai",
            "मेरा नाम सुनीता है|मेरा नाम [removed] है",
    })
    void removesPersonalData(String in, String out) {
        assertThat(PiiRedactor.redact(in, null)).isEqualTo(out);
    }

    @Test
    void keepsReferenceNumbersPinCodesAndWards() {
        String text = "status of JN-2026-000123, PIN 824236, ward 12";
        assertThat(PiiRedactor.redact(text, null)).isEqualTo(text);
        assertThat(PiiRedactor.redact("JN 2026 000123 9876543210", null)).isEqualTo("JN 2026 000123 [removed]");
    }

    @Test
    void removesTheUsersOwnNameInAnyCase() {
        assertThat(PiiRedactor.redact("asha here, pothole near ASHA's house", "Asha Rao"))
                .isEqualTo("[removed] here, pothole near [removed]'s house");
        // short name parts (2 letters) are left alone - they would hit ordinary words
        assertThat(PiiRedactor.redact("the road is broken", "Al Ed")).isEqualTo("the road is broken");
        assertThat(PiiRedactor.containsPersonalData("call 9876543210", null)).isTrue();
        assertThat(PiiRedactor.containsPersonalData("pothole on main road", "Asha Rao")).isFalse();
    }

    @Test
    void nullAndBlankArePassedThrough() {
        assertThat(PiiRedactor.redact(null, "x")).isNull();
        assertThat(PiiRedactor.redact("  ", "x")).isEqualTo("  ");
    }
}
