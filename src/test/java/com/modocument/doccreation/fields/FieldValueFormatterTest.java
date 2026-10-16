package com.modocument.doccreation.fields;

import com.modocument.doccreation.pdftemplate.RegionKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How values are drawn and stored (field-validation-contract §2): dates and
 * numbers in their field's format, anything unparseable as typed; record
 * values normalised to the stored shape only when they parse.
 */
class FieldValueFormatterTest {

    private static FieldProps dateFormat(String pattern) {
        return new FieldProps(null, null, null, null, pattern, null, null);
    }

    private static FieldProps numberFormat(Integer decimals, Boolean thousands, String prefix, String suffix) {
        return new FieldProps(null, null, null, null, null,
                new FieldProps.NumberFormatSpec(decimals, thousands, prefix, suffix), null);
    }

    @Test
    void datesAreDrawnInTheFieldsFormatInEnglish() {
        assertThat(FieldValueFormatter.format(RegionKind.DATE, dateFormat("dd/MM/yyyy"), "2026-03-05"))
                .isEqualTo("05/03/2026");
        assertThat(FieldValueFormatter.format(RegionKind.DATE, dateFormat("d MMMM yyyy"), "2026-03-05"))
                .isEqualTo("5 March 2026");
        assertThat(FieldValueFormatter.format(RegionKind.DATE, dateFormat("MMMM d, yyyy"), " 2026-12-31 "))
                .isEqualTo("December 31, 2026");
        // Not an ISO date, no format, or a format that does not work: as typed.
        assertThat(FieldValueFormatter.format(RegionKind.DATE, dateFormat("dd/MM/yyyy"), "05/03/2026"))
                .isEqualTo("05/03/2026");
        assertThat(FieldValueFormatter.format(RegionKind.DATE, FieldProps.NONE, "2026-03-05"))
                .isEqualTo("2026-03-05");
        assertThat(FieldValueFormatter.format(RegionKind.DATE, dateFormat("bb"), "2026-03-05"))
                .isEqualTo("2026-03-05");
        // Only DATE fields are reformatted.
        assertThat(FieldValueFormatter.format(RegionKind.TEXT, dateFormat("dd/MM/yyyy"), "2026-03-05"))
                .isEqualTo("2026-03-05");
    }

    @Test
    void numbersAreRoundedHalfUpGroupedAndFramed() {
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(2, true, "Rs ", null), "1234567.125"))
                .isEqualTo("Rs 1,234,567.13");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(0, false, null, " %"), "12.5"))
                .isEqualTo("13 %");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(2, true, "$", null), "-1,000"))
                .isEqualTo("-$1,000.00");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(null, true, null, null), "999.5"))
                .as("no decimals set: the typed scale").isEqualTo("999.5");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(null, true, null, null), "100000"))
                .isEqualTo("100,000");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, numberFormat(2, true, null, null), "twelve"))
                .isEqualTo("twelve");
        assertThat(FieldValueFormatter.format(RegionKind.NUMBER, FieldProps.NONE, "1234.5")).isEqualTo("1234.5");
    }

    @Test
    void recordDatesInCommonLayoutsBecomeIso() {
        String[] layouts = {"05/03/2026", "5/3/2026", "2026-03-05", "05-03-2026", "05.03.2026", "5 Mar 2026",
                "5 march 2026", "Mar 5, 2026", "March 5, 2026", "2026-03-05T00:00:00"};
        for (String layout : layouts) {
            assertThat(FieldValueFormatter.normalize(RegionKind.DATE, layout)).as(layout).isEqualTo("2026-03-05");
        }
        assertThat(FieldValueFormatter.normalize(RegionKind.DATE, "31/02/2026")).isEqualTo("31/02/2026");
        assertThat(FieldValueFormatter.normalize(RegionKind.DATE, "soon")).isEqualTo("soon");
        assertThat(FieldValueFormatter.normalize(RegionKind.TEXT, "05/03/2026")).isEqualTo("05/03/2026");
    }

    @Test
    void recordNumbersBecomePlainDecimalsOnlyWhenUnambiguous() {
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "1,234,567.50")).isEqualTo("1234567.50");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "Rs 1,000")).isEqualTo("1000");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "1 000 MUR")).isEqualTo("1000");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "$25.5")).isEqualTo("25.5");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "-42")).isEqualTo("-42");
        // A decimal comma, an odd grouping or text: kept as typed.
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "1,5")).isEqualTo("1,5");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "1.234,56")).isEqualTo("1.234,56");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "12 kg")).isEqualTo("12 kg");
        assertThat(FieldValueFormatter.normalize(RegionKind.NUMBER, "n/a")).isEqualTo("n/a");
    }

    @Test
    void recordCheckboxValuesBecomeTrueOrEmpty() {
        assertThat(FieldValueFormatter.normalize(RegionKind.CHECKBOX, "Yes")).isEqualTo("true");
        assertThat(FieldValueFormatter.normalize(RegionKind.CHECKBOX, "X")).isEqualTo("true");
        assertThat(FieldValueFormatter.normalize(RegionKind.CHECKBOX, "no")).isEmpty();
        assertThat(FieldValueFormatter.normalize(RegionKind.CHECKBOX, "maybe")).isEqualTo("maybe");
    }

    @Test
    void propsCodecReadsNothingOnBadJsonAndStoresEmptyAsNull() {
        assertThat(FieldPropsCodec.read(null)).isEqualTo(FieldProps.NONE);
        assertThat(FieldPropsCodec.read("{oops")).isEqualTo(FieldProps.NONE);
        assertThat(FieldPropsCodec.read("[1]")).isEqualTo(FieldProps.NONE);
        FieldProps props = numberFormat(2, true, "Rs ", null);
        assertThat(FieldPropsCodec.read(FieldPropsCodec.write(props))).isEqualTo(props);
        assertThat(FieldPropsCodec.read("{\"dateFormat\":\"dd/MM/yyyy\",\"unknown\":1}").dateFormat())
                .isEqualTo("dd/MM/yyyy");
        assertThat(FieldPropsCodec.write(FieldProps.NONE)).isNull();
        assertThat(FieldPropsCodec.write(null)).isNull();
    }
}
