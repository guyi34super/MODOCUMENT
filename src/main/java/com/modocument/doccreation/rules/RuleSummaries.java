package com.modocument.doccreation.rules;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-language summaries of field rules for the builder's Checks panel
 * (field-validation-contract §4.4), e.g. "1–40 characters" or "On or before
 * 18 years ago". Built only from the rule's settings and field labels the
 * template's author wrote; never from a document value. A setting that cannot
 * be read gives the rule type's generic summary instead of an error.
 */
public final class RuleSummaries {

    private static final int MAX_LISTED = 5;
    private static final int MAX_PATTERN = 40;
    private static final Pattern RELATIVE = Pattern.compile("([+-])(\\d{1,4})([dwmy])");

    private RuleSummaries() {
    }

    /**
     * @param type   the rule type
     * @param config the rule's settings (may be null or not an object)
     * @param labels region key to label, for REQUIRED_IF / COMPARE
     */
    public static String summary(DocRuleType type, JsonNode config, Map<String, String> labels) {
        JsonNode c = config == null || !config.isObject() ? null : config;
        return switch (type) {
            case REQUIRED -> "Required";
            case EMAIL -> "E-mail address";
            case EXPIRY_DATE -> "A future date";
            case REGEX -> {
                String pattern = text(c, "Pattern");
                yield pattern == null ? "Matches a pattern"
                        : "Matches the pattern " + (pattern.length() > MAX_PATTERN
                        ? pattern.substring(0, MAX_PATTERN) + "…" : pattern);
            }
            case LENGTH -> bounds(text(c, "Min"), text(c, "Max"), " characters", "Any length");
            case RANGE -> {
                String min = text(c, "Min");
                String max = text(c, "Max");
                if (min != null && max != null) {
                    yield "Between " + min + " and " + max;
                }
                yield min != null ? "At least " + min : max != null ? "At most " + max : "A number";
            }
            case ALLOWED_VALUES -> "One of: " + listed(c, "Values", "the allowed values");
            case DATE_FORMAT -> "A date like " + listedOr(c, "Formats", "yyyy-MM-dd");
            case DATE_RANGE -> {
                String min = date(text(c, "Min"));
                String max = date(text(c, "Max"));
                if (min != null && max != null) {
                    yield "Between " + min + " and " + max;
                }
                yield min != null ? "On or after " + min : max != null ? "On or before " + max : "A date";
            }
            case REQUIRED_IF -> {
                String other = label(labels, text(c, "Field"));
                String equals = text(c, "Equals");
                yield equals == null ? "Required when " + other + " is filled"
                        : "Required when " + other + " is " + equals;
            }
            case COMPARE -> "Must be " + symbol(text(c, "Op")) + " " + label(labels, text(c, "Field"));
        };
    }

    private static String bounds(String min, String max, String unit, String none) {
        if (min != null && max != null) {
            return min + "–" + max + unit;
        }
        if (min != null) {
            return "At least " + min + unit;
        }
        return max != null ? "At most " + max + unit : none;
    }

    private static String listed(JsonNode c, String name, String fallback) {
        List<String> items = items(c, name);
        if (items.isEmpty()) {
            return fallback;
        }
        String shown = String.join(", ", items.subList(0, Math.min(MAX_LISTED, items.size())));
        return items.size() > MAX_LISTED ? shown + ", …" : shown;
    }

    private static String listedOr(JsonNode c, String name, String fallback) {
        List<String> items = items(c, name);
        return items.isEmpty() ? fallback : String.join(" or ", items.subList(0, Math.min(MAX_LISTED, items.size())));
    }

    private static List<String> items(JsonNode c, String name) {
        List<String> out = new ArrayList<>();
        JsonNode list = c == null ? null : c.get(name);
        if (list != null && list.isArray()) {
            list.forEach(n -> {
                if (n.isValueNode() && !n.asText().isBlank()) {
                    out.add(n.asText().strip());
                }
            });
        }
        return out;
    }

    /** ISO dates as written; {@code today} and {@code ±N(d|w|m|y)} in words. */
    static String date(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("today")) {
            return "today";
        }
        Matcher m = RELATIVE.matcher(v);
        if (!m.matches()) {
            return raw.strip();
        }
        int n = Integer.parseInt(m.group(2));
        String unit = switch (m.group(3)) {
            case "d" -> "day";
            case "w" -> "week";
            case "m" -> "month";
            default -> "year";
        };
        String amount = n + " " + unit + (n == 1 ? "" : "s");
        if (n == 0) {
            return "today";
        }
        return m.group(1).equals("-") ? amount + " ago" : amount + " from today";
    }

    private static String symbol(String op) {
        if (op == null) {
            return "compared with";
        }
        return switch (op.strip()) {
            case "<" -> "<";
            case "<=" -> "≤";
            case "=" -> "equal to";
            case "!=" -> "different from";
            case ">=" -> "≥";
            case ">" -> ">";
            default -> "compared with";
        };
    }

    private static String label(Map<String, String> labels, String key) {
        if (key == null || key.isBlank()) {
            return "another field";
        }
        String label = labels == null ? null : labels.get(key);
        return label == null || label.isBlank() ? key : label;
    }

    private static String text(JsonNode c, String name) {
        JsonNode node = c == null ? null : c.get(name);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String s = node.asText();
        return s.isBlank() ? null : s.strip();
    }
}
