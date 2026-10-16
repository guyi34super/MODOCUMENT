package com.modocument.doccreation.fields;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The extra properties of a template field (V320 {@code FieldPropsJson},
 * docs/doccreation/field-validation-contract.md §2). Every member is
 * optional; {@code null} means "not set".
 *
 * @param placeholder  hint shown in an empty input (≤200)
 * @param helpText     help shown under the input (≤500)
 * @param defaultValue the value a new document starts with (≤2000)
 * @param options      the choices of a CHOICE field (≤200, unique, each ≤200)
 * @param dateFormat   how a DATE value (stored ISO yyyy-MM-dd) is drawn in the PDF (whitelist)
 * @param numberFormat how a NUMBER value is drawn in the PDF
 * @param maxLength    the longest value allowed (1–20000), checked on validation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldProps(String placeholder,
                         String helpText,
                         String defaultValue,
                         List<String> options,
                         String dateFormat,
                         NumberFormatSpec numberFormat,
                         Integer maxLength) {

    /** No properties set. */
    public static final FieldProps NONE = new FieldProps(null, null, null, null, null, null, null);

    /** The date output patterns a field may choose (contract §2). */
    public static final List<String> DATE_FORMATS = List.of(
            "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
            "d MMMM yyyy", "MMMM d, yyyy", "dd MMM yyyy");

    /**
     * How a number is drawn: {@code decimals} 0–6 (null = as typed),
     * {@code thousands} grouping with ",", {@code prefix}/{@code suffix} ≤5 chars.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NumberFormatSpec(Integer decimals, Boolean thousands, String prefix, String suffix) {
    }

    /** No property set. Not a JSON property ({@code @JsonIgnore}: it would serialize as "empty"). */
    @JsonIgnore
    public boolean isEmpty() {
        return NONE.equals(this);
    }
}
