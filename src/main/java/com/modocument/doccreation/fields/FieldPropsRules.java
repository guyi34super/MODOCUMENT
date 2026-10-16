package com.modocument.doccreation.fields;

import com.modocument.doccreation.pdftemplate.RegionKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks the properties of one template field against its kind
 * (docs/doccreation/field-validation-contract.md §2), for the overlay save.
 * A limit that is exceeded is refused with an {@link IllegalArgumentException}
 * whose message starts {@code Field "<key>": } (a 400); a property that does
 * not fit the field's kind is dropped (stored as null), except that a CHOICE
 * field without options is refused. A default value must pass the built-in
 * checks of its kind (§4.2).
 */
public final class FieldPropsRules {

    public static final int MAX_PLACEHOLDER = 200;
    public static final int MAX_HELP_TEXT = 500;
    public static final int MAX_DEFAULT_VALUE = 2000;
    public static final int MAX_OPTIONS = 200;
    public static final int MAX_OPTION_LENGTH = 200;
    public static final int MAX_DECIMALS = 6;
    public static final int MAX_AFFIX = 5;
    public static final int MAX_LENGTH_LIMIT = 20000;

    private FieldPropsRules() {
    }

    /**
     * The properties as they are stored for a field of {@code kind}: blank
     * texts become null, options are trimmed, properties of other kinds are
     * dropped. {@link FieldProps#NONE} when nothing is left.
     *
     * @throws IllegalArgumentException for a limit exceeded, a CHOICE without
     *     options or a default value its kind refuses
     */
    public static FieldProps check(String key, RegionKind kind, FieldProps props) {
        FieldProps in = props == null ? FieldProps.NONE : props;
        String prefix = "Field \"" + key + "\": ";
        String placeholder = limited(in.placeholder(), MAX_PLACEHOLDER, prefix + "the placeholder");
        String helpText = limited(in.helpText(), MAX_HELP_TEXT, prefix + "the help text");
        String defaultValue = in.defaultValue() == null || in.defaultValue().isBlank() ? null : in.defaultValue();
        if (defaultValue != null && defaultValue.length() > MAX_DEFAULT_VALUE) {
            throw new IllegalArgumentException(prefix + "the default value is limited to " + MAX_DEFAULT_VALUE
                    + " characters.");
        }
        List<String> options = kind == RegionKind.CHOICE ? options(in.options(), prefix) : null;
        if (kind == RegionKind.CHOICE && options.isEmpty()) {
            throw new IllegalArgumentException(prefix + "a dropdown needs at least one option.");
        }
        String dateFormat = null;
        if (kind == RegionKind.DATE && in.dateFormat() != null && !in.dateFormat().isBlank()) {
            dateFormat = in.dateFormat().strip();
            if (!FieldProps.DATE_FORMATS.contains(dateFormat)) {
                throw new IllegalArgumentException(prefix + "the date format must be one of "
                        + String.join(", ", FieldProps.DATE_FORMATS) + ".");
            }
        }
        FieldProps.NumberFormatSpec numberFormat = kind == RegionKind.NUMBER
                ? numberFormat(in.numberFormat(), prefix) : null;
        Integer maxLength = null;
        if (isText(kind) && in.maxLength() != null) {
            maxLength = in.maxLength();
            if (maxLength < 1 || maxLength > MAX_LENGTH_LIMIT) {
                throw new IllegalArgumentException(prefix + "the maximum length must be between 1 and "
                        + MAX_LENGTH_LIMIT + ".");
            }
        }
        if (kind == RegionKind.IMAGE || kind == RegionKind.SIGNATURE) {
            defaultValue = null;
        }
        if (defaultValue != null) {
            defaultValue = checkDefault(prefix, kind, defaultValue, options, maxLength);
        }
        FieldProps out = new FieldProps(placeholder, helpText, defaultValue, options, dateFormat, numberFormat,
                maxLength);
        return out.isEmpty() ? FieldProps.NONE : out;
    }

    /** TEXT, MULTILINE and CHOICE: the kinds a maximum length applies to. */
    public static boolean isText(RegionKind kind) {
        return kind == RegionKind.TEXT || kind == RegionKind.MULTILINE || kind == RegionKind.CHOICE;
    }

    private static String checkDefault(String prefix, RegionKind kind, String value, List<String> options,
                                       Integer maxLength) {
        switch (kind) {
            case DATE -> {
                try {
                    LocalDate.parse(value.strip());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(prefix + "the default value must be a date written "
                            + "yyyy-MM-dd.");
                }
                return value.strip();
            }
            case NUMBER -> {
                try {
                    new BigDecimal(value.strip().replace(",", ""));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(prefix + "the default value must be a number.");
                }
                return value.strip();
            }
            case CHOICE -> {
                if (!options.contains(value.strip())) {
                    throw new IllegalArgumentException(prefix + "the default value must be one of the options.");
                }
                return value.strip();
            }
            case CHECKBOX -> {
                if (!"true".equals(value.strip().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(prefix + "the default value of a checkbox must be true "
                            + "(ticked) or empty.");
                }
                return "true";
            }
            default -> {
                if (maxLength != null && value.codePointCount(0, value.length()) > maxLength) {
                    throw new IllegalArgumentException(prefix + "the default value is longer than the maximum "
                            + "length.");
                }
                return value;
            }
        }
    }

    private static List<String> options(List<String> raw, String prefix) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException(prefix + "a dropdown has at most " + MAX_OPTIONS + " options.");
        }
        Set<String> seen = new HashSet<>();
        for (String option : raw) {
            String clean = option == null ? "" : option.strip();
            if (clean.isEmpty() || clean.length() > MAX_OPTION_LENGTH) {
                throw new IllegalArgumentException(prefix + "each option must have 1 to " + MAX_OPTION_LENGTH
                        + " characters.");
            }
            if (!seen.add(clean)) {
                throw new IllegalArgumentException(prefix + "the option \"" + clean + "\" is listed twice.");
            }
            out.add(clean);
        }
        return out;
    }

    private static FieldProps.NumberFormatSpec numberFormat(FieldProps.NumberFormatSpec spec, String prefix) {
        if (spec == null) {
            return null;
        }
        if (spec.decimals() != null && (spec.decimals() < 0 || spec.decimals() > MAX_DECIMALS)) {
            throw new IllegalArgumentException(prefix + "the number of decimals must be between 0 and "
                    + MAX_DECIMALS + ".");
        }
        String prefixText = affix(spec.prefix(), prefix + "the number prefix");
        String suffixText = affix(spec.suffix(), prefix + "the number suffix");
        if (spec.decimals() == null && spec.thousands() == null && prefixText == null && suffixText == null) {
            return null;
        }
        return new FieldProps.NumberFormatSpec(spec.decimals(), spec.thousands(), prefixText, suffixText);
    }

    private static String affix(String value, String what) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_AFFIX) {
            throw new IllegalArgumentException(what + " is limited to " + MAX_AFFIX + " characters.");
        }
        return value;
    }

    private static String limited(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(what + " is limited to " + max + " characters.");
        }
        return value;
    }
}
