package com.modocument.infrastructure.transform.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.modocument.infrastructure.transform.code.NumberNormalizer.Style.COMMA_DECIMAL;
import static com.modocument.infrastructure.transform.code.NumberNormalizer.Style.POINT_DECIMAL;
import static org.assertj.core.api.Assertions.assertThat;

class NumberNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1,234.56|-|1234.56",
            "-1,234.56|-|-1234.56",
            "+42|-|42",
            "(1,234.50)|-|-1234.50",
            "1,234.50-|-|-1234.50",
            "$ 1,000|-|1000",
            "MUR 45,000.00|-|45000.00",
            "45,000.00 USD|-|45000.00",
            "Rs 12.5|-|12.5",
            ".5|-|0.5",
            "12,34,567.89|-|1234567.89",
            "1 234.56|-|1234.56",
            "-0.00|-|0.00",
            "abc|-|abc",
            "12/03/2024|-|12/03/2024",
            "1,23.4|-|1,23.4"})
    void normalisesPointDecimalValues(String raw, String ignored, String expected) {
        assertThat(NumberNormalizer.normalize(raw, POINT_DECIMAL)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.234,56|1234.56",
            "-45,10|-45.10",
            "2.500,00|2500.00",
            "1 234,56|1234.56",
            "1.234.567|1234567",
            "€ 12,5|12.5",
            "12,5.3|12,5.3"})
    void normalisesCommaDecimalValues(String raw, String expected) {
        assertThat(NumberNormalizer.normalize(raw, COMMA_DECIMAL)).isEqualTo(expected);
    }

    @Test
    void detectsTheColumnStyleFromUnambiguousValues() {
        assertThat(NumberNormalizer.detect(List.of("1.000,00", "-45,10", "12,35"))).isEqualTo(COMMA_DECIMAL);
        assertThat(NumberNormalizer.detect(List.of("1,000.00", "45.1", "12"))).isEqualTo(POINT_DECIMAL);
        assertThat(NumberNormalizer.detect(List.of("1.234.567", "2"))).isEqualTo(COMMA_DECIMAL);
        assertThat(NumberNormalizer.detect(List.of("0,125"))).isEqualTo(COMMA_DECIMAL);
    }

    @Test
    void aLoneThreeDigitGroupNeverSwitchesToDecimalComma() {
        // "1.234" could be 1234 or 1.234: without evidence the decimal point stands.
        assertThat(NumberNormalizer.detect(List.of("1.234", "5.678"))).isEqualTo(POINT_DECIMAL);
        assertThat(NumberNormalizer.normalizeColumn(List.of("1.234", "5.678"))).containsExactly("1.234", "5.678");
        assertThat(NumberNormalizer.normalizeColumn(List.of("1,234", "5,678"))).containsExactly("1234", "5678");
    }

    @Test
    void normalisesAWholeColumnWithOneStyle() {
        assertThat(NumberNormalizer.normalizeColumn(List.of("1.000,00", "-45,10", "", "n/a")))
                .containsExactly("1000.00", "-45.10", "", "n/a");
        assertThat(NumberNormalizer.isPlainNumber("-1000.00")).isTrue();
        assertThat(NumberNormalizer.isPlainNumber("1,000")).isFalse();
    }
}
