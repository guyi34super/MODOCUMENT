package com.modocument.infrastructure.transform.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DateNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2024-01-15|2024-01-15",
            "2024-01-15T10:30:00Z|2024-01-15",
            "2024-01-15 10:30|2024-01-15",
            "2024/01/15|2024-01-15",
            "20240115|2024-01-15",
            "15 Jan 2024|2024-01-15",
            "15-JAN-2024|2024-01-15",
            "January 5, 2022|2022-01-05",
            "Mon, 15 Jan 2024|2024-01-15",
            "5 March 2021|2021-03-05",
            "15/01/2024|2024-01-15",
            "01/15/2024|2024-01-15",
            "15.01.2024|2024-01-15",
            "05/05/2024|2024-05-05"})
    void readsUnambiguousDates(String raw, String expected) {
        assertThat(DateNormalizer.normalize(raw, List.of())).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "03/04/2024",
            "03-04-2024",
            "01/02/24",
            "31/02/2024",
            "2024-02-30",
            "next Tuesday",
            "13/13/2024"})
    void leavesAmbiguousOrInvalidDatesRaw(String raw) {
        assertThat(DateNormalizer.normalize(raw, List.of())).isEqualTo(raw);
    }

    @Test
    void fieldFormatsComeFirstAndYearOfEraIsAccepted() {
        assertThat(DateNormalizer.normalize("03.04.2021", List.of("dd.MM.yyyy"))).isEqualTo("2021-04-03");
        assertThat(DateNormalizer.normalize("03/04/2024", List.of("MM/dd/yyyy"))).isEqualTo("2024-03-04");
        assertThat(DateNormalizer.normalize("040321", List.of("ddMMyy"))).isEqualTo("2021-03-04");
        // An unusable author format is skipped, the built-ins still apply.
        assertThat(DateNormalizer.normalize("2024-01-15", List.of("not a {format"))).isEqualTo("2024-01-15");
    }

    @Test
    void aDisambiguatingValueSettlesTheColumnOrder() {
        assertThat(DateNormalizer.normalizeColumn(List.of("03/01/2024", "15/01/2024", "07/02/2024"), List.of()))
                .containsExactly("2024-01-03", "2024-01-15", "2024-02-07");
        assertThat(DateNormalizer.normalizeColumn(List.of("03/01/2024", "01/15/2024"), List.of()))
                .containsExactly("2024-03-01", "2024-01-15");
    }

    @Test
    void conflictingColumnEvidenceLeavesAmbiguousValuesRaw() {
        assertThat(DateNormalizer.normalizeColumn(
                List.of("03/01/2024", "15/01/2024", "01/15/2024", ""), List.of()))
                .containsExactly("03/01/2024", "2024-01-15", "2024-01-15", "");
    }

    @Test
    void isIsoDateIsStrict() {
        assertThat(DateNormalizer.isIsoDate("2024-02-29")).isTrue();
        assertThat(DateNormalizer.isIsoDate("2023-02-29")).isFalse();
        assertThat(DateNormalizer.isIsoDate("15/01/2024")).isFalse();
    }
}
