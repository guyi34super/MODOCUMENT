package com.modocument.doccreation.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.validation.SafeRegex;
import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.dto.ValidationResultDto.Issue;
import com.modocument.doccreation.dto.ValidationResultDto.SetupWarning;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.fields.FieldValueFormatter;
import com.modocument.doccreation.pdftemplate.RegionKind;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The checks of document-creation fields (docs/doccreation/field-validation-contract.md
 * §3 and §4.2): the built-in kind checks of every region, then the
 * blueprint's rules of the keys the regions have.
 *
 * <ul>
 *   <li>Every rule except REQUIRED and REQUIRED_IF passes a blank value.</li>
 *   <li>Numbers are BigDecimals with {@code ,} removed; dates are ISO, and
 *       rule bounds may also be {@code today} or {@code ±N(d|w|m|y)} from the
 *       injected {@link Clock}.</li>
 *   <li>A rule that names a missing field, does not fit its field's kind or
 *       has unusable settings is skipped and reported as a setup warning; it
 *       never throws.</li>
 *   <li>Messages use the field's label (or the rule's own message) and never
 *       contain the value.</li>
 * </ul>
 */
@Component
public class DefaultDocRuleEvaluator implements DocRuleEvaluator {

    public static final String STATE_VALIDATED = "VALIDATED";
    public static final String STATE_DRAFT = "DRAFT";

    /** Built-in check codes (contract §4.2). */
    public static final String DATE_INVALID = "DATE_INVALID";
    public static final String NUMBER_INVALID = "NUMBER_INVALID";
    public static final String CHOICE_INVALID = "CHOICE_INVALID";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String CHECKBOX_INVALID = "CHECKBOX_INVALID";

    /** The REGEX deadline per value; a timeout fails the rule. */
    static final long REGEX_TIMEOUT_MILLIS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern RELATIVE_DATE = Pattern.compile("([+-]?)(\\d{1,4})\\s*([dwmy])",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> OPS = Set.of("<", "<=", "=", "!=", ">=", ">");
    private static final int MAX_LISTED_VALUES = 10;
    private static final LocalDate EXAMPLE_DATE = LocalDate.of(2026, 12, 31);

    private final Clock clock;

    public DefaultDocRuleEvaluator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ValidationResultDto evaluate(List<RegionView> regions, Map<String, String> values,
                                        Set<String> placedImageKeys, List<DocFieldRule> rules, int rulesRevision) {
        Map<String, RegionView> byKey = new LinkedHashMap<>();
        for (RegionView region : regions == null ? List.<RegionView>of() : regions) {
            byKey.putIfAbsent(region.key(), region);
        }
        Context ctx = new Context(byKey, values == null ? Map.of() : values,
                placedImageKeys == null ? Set.of() : placedImageKeys, LocalDate.now(clock));
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        List<SetupWarning> setup = new ArrayList<>();

        Set<String> builtInFailed = new HashSet<>();
        for (RegionView region : byKey.values()) {
            Issue issue = builtIn(region, ctx.value(region.key()));
            if (issue != null) {
                errors.add(issue);
                builtInFailed.add(region.key());
            }
        }

        List<String> order = new ArrayList<>(byKey.keySet());
        List<DocFieldRule> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        sorted.removeIf(Objects::isNull);
        sorted.sort(Comparator
                .comparingInt((DocFieldRule r) -> {
                    int index = order.indexOf(r.getRegionKey());
                    return index < 0 ? Integer.MAX_VALUE : index;
                })
                .thenComparingInt(DocFieldRule::getSortOrder)
                .thenComparing(r -> r.getDocFieldRuleId() == null ? Long.MAX_VALUE : r.getDocFieldRuleId()));
        for (DocFieldRule rule : sorted) {
            Outcome outcome = check(rule, ctx, builtInFailed);
            if (outcome instanceof Outcome.Setup s) {
                setup.add(new SetupWarning(rule.getDocFieldRuleId(), rule.getRegionKey(), s.message()));
            } else if (outcome instanceof Outcome.Failed f) {
                RegionView region = byKey.get(rule.getRegionKey());
                String message = rule.getMessage() == null || rule.getMessage().isBlank()
                        ? f.message() : rule.getMessage().strip();
                Issue issue = new Issue(region.key(), region.label(), rule.getDocFieldRuleId(),
                        f.code(), message);
                if (isWarning(rule)) {
                    warnings.add(issue);
                } else {
                    errors.add(issue);
                }
            }
        }
        return new ValidationResultDto(errors.isEmpty() ? STATE_VALIDATED : STATE_DRAFT, null, rulesRevision,
                false, List.copyOf(errors), List.copyOf(warnings), List.copyOf(setup), true);
    }

    /**
     * Whether a rule type applies to a field kind — the same table the rules
     * API shows ({@link RuleTypeCatalog#fits}), so a rule the editor offers
     * is never a setup warning here (contract §3).
     */
    public static boolean appliesTo(DocRuleType type, RegionKind kind) {
        return type != null && RuleTypeCatalog.fits(type, kind);
    }

    // ------------------------------------------------------------- built-ins

    private static Issue builtIn(RegionView region, String value) {
        if (isBlank(value) || isPhoto(region.kind())) {
            return null;
        }
        String label = region.label();
        String t = value.strip();
        RegionKind kind = region.kind();
        FieldProps props = region.props();
        if (kind == RegionKind.DATE && FieldValueFormatter.parseIsoDate(t) == null) {
            return builtInIssue(region, DATE_INVALID, label + " must be a valid date.");
        }
        if (kind == RegionKind.NUMBER && FieldValueFormatter.parseNumber(t) == null) {
            return builtInIssue(region, NUMBER_INVALID, label + " must be a number.");
        }
        if (kind == RegionKind.CHECKBOX && !"true".equalsIgnoreCase(t)) {
            return builtInIssue(region, CHECKBOX_INVALID, label + " must be ticked or left empty.");
        }
        if (isChoice(kind) && props.options() != null && !props.options().isEmpty()
                && !props.options().contains(value) && !props.options().contains(t)) {
            return builtInIssue(region, CHOICE_INVALID, label + " must be one of its options.");
        }
        if (props.maxLength() != null && value.codePointCount(0, value.length()) > props.maxLength()) {
            return builtInIssue(region, TOO_LONG, label + " must be at most " + props.maxLength() + " characters.");
        }
        return null;
    }

    private static Issue builtInIssue(RegionView region, String code, String message) {
        return new Issue(region.key(), region.label(), null, code, message);
    }

    // ----------------------------------------------------------------- rules

    /** The result of one rule: passed, failed (code + default message) or skipped with a setup warning. */
    private sealed interface Outcome {
        Outcome PASSED = new Passed();

        record Passed() implements Outcome {
        }

        record Failed(String code, String message) implements Outcome {
        }

        record Setup(String message) implements Outcome {
        }
    }

    private record Context(Map<String, RegionView> regions, Map<String, String> values, Set<String> placed,
                           LocalDate today) {

        String value(String key) {
            return values.get(key);
        }

        /** A photo field holds a photo, a checkbox is ticked, any other field is not blank. */
        boolean filled(RegionView region) {
            if (isPhoto(region.kind())) {
                return placed.contains(region.key());
            }
            String value = value(region.key());
            if (region.kind() == RegionKind.CHECKBOX) {
                return value != null && "true".equalsIgnoreCase(value.strip());
            }
            return !isBlank(value);
        }
    }

    /** Thrown inside a check for unusable settings; becomes a setup warning. */
    private static final class BadConfig extends Exception {
        BadConfig(String message) {
            super(message, null, false, false);
        }
    }

    private Outcome check(DocFieldRule rule, Context ctx, Set<String> builtInFailed) {
        DocRuleType type = DocRuleType.parse(rule.getRuleType()).orElse(null);
        String typeName = type == null ? truncate(String.valueOf(rule.getRuleType()), 32) : type.name();
        if (type == null) {
            return new Outcome.Setup("Rule type \"" + typeName + "\" is not known; skipped.");
        }
        RegionView region = ctx.regions().get(rule.getRegionKey());
        if (region == null) {
            return new Outcome.Setup(typeName + " is set for field \"" + truncate(String.valueOf(rule.getRegionKey()),
                    64) + "\", which this version of the template does not have; skipped.");
        }
        if (!appliesTo(type, region.kind())) {
            return new Outcome.Setup(typeName + " does not apply to " + kindPhrase(region.kind()) + "; skipped.");
        }
        JsonNode config;
        try {
            config = readConfig(rule.getConfigJson());
        } catch (BadConfig e) {
            return new Outcome.Setup(typeName + " on " + region.label() + " has unreadable settings; skipped.");
        }
        String value = ctx.value(region.key());
        if (!type.checksBlank() && (isBlank(value) || builtInFailed.contains(region.key()))) {
            return Outcome.PASSED;
        }
        String label = region.label();
        try {
            return switch (type) {
                case REQUIRED -> ctx.filled(region) ? Outcome.PASSED : fail(type, label + " is required.");
                case REGEX -> regex(type, config, label, value.strip());
                case EMAIL -> EMAIL.matcher(value.strip()).matches() ? Outcome.PASSED
                        : fail(type, label + " must be an e-mail address.");
                case LENGTH -> length(type, config, label, value.strip());
                case RANGE -> range(type, config, label, value);
                case ALLOWED_VALUES -> allowed(type, config, label, value.strip());
                case DATE_FORMAT -> dateFormat(type, config, label, value.strip());
                case DATE_RANGE -> dateRange(type, config, label, value, region, ctx.today());
                case EXPIRY_DATE -> {
                    LocalDate date = FieldValueFormatter.parseRecordDate(value);
                    yield date != null && date.isAfter(ctx.today()) ? Outcome.PASSED
                            : fail(type, label + " must be a future date.");
                }
                case REQUIRED_IF -> requiredIf(type, config, label, region, ctx);
                case COMPARE -> compare(type, config, label, value, region, ctx);
            };
        } catch (BadConfig e) {
            return new Outcome.Setup(typeName + " on " + label + " " + e.getMessage() + "; skipped.");
        }
    }

    private static Outcome fail(DocRuleType type, String message) {
        return new Outcome.Failed(type.name(), message);
    }

    private static Outcome regex(DocRuleType type, JsonNode config, String label, String value) throws BadConfig {
        String source = text(config, "Pattern");
        if (source == null || source.isEmpty()) {
            throw new BadConfig("has no pattern");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(source);
        } catch (PatternSyntaxException e) {
            throw new BadConfig("has a pattern that does not compile");
        }
        boolean found;
        try {
            found = SafeRegex.find(pattern, value, REGEX_TIMEOUT_MILLIS);
        } catch (SafeRegex.RegexTimeoutException | StackOverflowError e) {
            found = false;
        }
        return found ? Outcome.PASSED : fail(type, label + " is not in the expected format.");
    }

    private static Outcome length(DocRuleType type, JsonNode config, String label, String value) throws BadConfig {
        BigDecimal min = number(config, "Min");
        BigDecimal max = number(config, "Max");
        int count = value.codePointCount(0, value.length());
        boolean ok = (min == null || BigDecimal.valueOf(count).compareTo(min) >= 0)
                && (max == null || BigDecimal.valueOf(count).compareTo(max) <= 0);
        if (ok) {
            return Outcome.PASSED;
        }
        String message;
        if (min != null && max != null) {
            message = label + " must be between " + plain(min) + " and " + plain(max) + " characters.";
        } else if (min != null) {
            message = label + " must be at least " + plain(min) + " characters.";
        } else {
            message = label + " must be at most " + plain(max) + " characters.";
        }
        return fail(type, message);
    }

    private static Outcome range(DocRuleType type, JsonNode config, String label, String value) throws BadConfig {
        BigDecimal min = number(config, "Min");
        BigDecimal max = number(config, "Max");
        BigDecimal number = FieldValueFormatter.parseNumber(value);
        boolean ok = number != null && (min == null || number.compareTo(min) >= 0)
                && (max == null || number.compareTo(max) <= 0);
        if (ok) {
            return Outcome.PASSED;
        }
        String message;
        if (min != null && max != null) {
            message = label + " must be a number between " + plain(min) + " and " + plain(max) + ".";
        } else if (min != null) {
            message = label + " must be a number of at least " + plain(min) + ".";
        } else if (max != null) {
            message = label + " must be a number of at most " + plain(max) + ".";
        } else {
            message = label + " must be a number.";
        }
        return fail(type, message);
    }

    private static Outcome allowed(DocRuleType type, JsonNode config, String label, String value) throws BadConfig {
        JsonNode list = config.get("Values");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new BadConfig("has no values");
        }
        boolean ignoreCase = config.path("IgnoreCase").asBoolean(false);
        List<String> allowed = new ArrayList<>();
        for (JsonNode entry : list) {
            if (entry != null && entry.isValueNode() && !entry.isNull()) {
                allowed.add(entry.asText().strip());
            }
        }
        if (allowed.isEmpty()) {
            throw new BadConfig("has no values");
        }
        for (String candidate : allowed) {
            if (ignoreCase ? candidate.equalsIgnoreCase(value) : candidate.equals(value)) {
                return Outcome.PASSED;
            }
        }
        List<String> shown = allowed.size() > MAX_LISTED_VALUES ? allowed.subList(0, MAX_LISTED_VALUES) : allowed;
        String listed = String.join(", ", shown) + (allowed.size() > MAX_LISTED_VALUES ? ", …" : "");
        return fail(type, label + " must be one of: " + listed + ".");
    }

    private static Outcome dateFormat(DocRuleType type, JsonNode config, String label, String value)
            throws BadConfig {
        List<String> patterns = new ArrayList<>();
        JsonNode list = config.get("Formats");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw new BadConfig("has unreadable formats");
            }
            for (JsonNode entry : list) {
                if (entry != null && entry.isTextual() && !entry.asText().isBlank()) {
                    patterns.add(entry.asText());
                }
            }
        }
        if (patterns.isEmpty()) {
            patterns.add("yyyy-MM-dd");
        }
        List<DateTimeFormatter> formats = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                formats.add(FieldValueFormatter.strict(pattern));
            } catch (IllegalArgumentException e) {
                throw new BadConfig("has a date format that is not valid");
            }
        }
        for (DateTimeFormatter format : formats) {
            try {
                format.parse(value);
                return Outcome.PASSED;
            } catch (DateTimeParseException e) {
                // next format
            }
        }
        String example;
        try {
            example = DateTimeFormatter.ofPattern(patterns.get(0), Locale.ENGLISH).format(EXAMPLE_DATE);
        } catch (RuntimeException e) {
            example = patterns.get(0);
        }
        return fail(type, label + " must be a date like " + example + ".");
    }

    private static Outcome dateRange(DocRuleType type, JsonNode config, String label, String value,
                                     RegionView region, LocalDate today) throws BadConfig {
        LocalDate min = dateBound(config, "Min", today);
        LocalDate max = dateBound(config, "Max", today);
        LocalDate date = FieldValueFormatter.parseRecordDate(value);
        boolean ok = date != null && (min == null || !date.isBefore(min)) && (max == null || !date.isAfter(max));
        if (ok) {
            return Outcome.PASSED;
        }
        String message;
        if (min != null && max != null) {
            message = label + " must be on or after " + show(min, region) + " and on or before "
                    + show(max, region) + ".";
        } else if (min != null) {
            message = label + " must be on or after " + show(min, region) + ".";
        } else if (max != null) {
            message = label + " must be on or before " + show(max, region) + ".";
        } else {
            message = label + " must be a date.";
        }
        return fail(type, message);
    }

    private static Outcome requiredIf(DocRuleType type, JsonNode config, String label, RegionView region,
                                      Context ctx) throws BadConfig {
        RegionView other = otherField(config, region, ctx);
        String equals = text(config, "Equals");
        boolean applies;
        if (equals == null) {
            applies = ctx.filled(other);
        } else {
            String otherValue = ctx.value(other.key());
            applies = otherValue != null && otherValue.strip().equalsIgnoreCase(equals.strip());
        }
        if (!applies || ctx.filled(region)) {
            return Outcome.PASSED;
        }
        return fail(type, equals == null
                ? label + " is required when " + other.label() + " is filled."
                : label + " is required when " + other.label() + " is " + equals.strip() + ".");
    }

    private static Outcome compare(DocRuleType type, JsonNode config, String label, String value,
                                   RegionView region, Context ctx) throws BadConfig {
        RegionView other = otherField(config, region, ctx);
        String op = text(config, "Op");
        if (op == null || !OPS.contains(op.strip())) {
            throw new BadConfig("has an unknown operator");
        }
        op = op.strip();
        String otherValue = ctx.value(other.key());
        if (isBlank(otherValue)) {
            return Outcome.PASSED;
        }
        int cmp;
        boolean dates = false;
        LocalDate thisDate = FieldValueFormatter.parseRecordDate(value);
        LocalDate otherDate = FieldValueFormatter.parseRecordDate(otherValue);
        BigDecimal thisNumber = FieldValueFormatter.parseNumber(value);
        BigDecimal otherNumber = FieldValueFormatter.parseNumber(otherValue);
        if (thisDate != null && otherDate != null) {
            cmp = thisDate.compareTo(otherDate);
            dates = true;
        } else if (thisNumber != null && otherNumber != null) {
            cmp = thisNumber.compareTo(otherNumber);
        } else if ("=".equals(op) || "!=".equals(op)) {
            cmp = value.strip().equals(otherValue.strip()) ? 0 : 1;
        } else {
            // Text cannot be ordered: only = and != compare text.
            return Outcome.PASSED;
        }
        boolean ok = switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">=" -> cmp >= 0;
            default -> cmp > 0;
        };
        return ok ? Outcome.PASSED : fail(type, label + " must be " + opWords(op, dates) + " " + other.label() + ".");
    }

    private static RegionView otherField(JsonNode config, RegionView region, Context ctx) throws BadConfig {
        String key = text(config, "Field");
        if (key == null || key.isBlank()) {
            throw new BadConfig("names no other field");
        }
        if (key.strip().equals(region.key())) {
            throw new BadConfig("names its own field");
        }
        RegionView other = ctx.regions().get(key.strip());
        if (other == null) {
            throw new BadConfig("names a field this version of the template does not have");
        }
        return other;
    }

    private static String opWords(String op, boolean dates) {
        return switch (op) {
            case "<" -> dates ? "before" : "less than";
            case "<=" -> dates ? "on or before" : "at most";
            case "=" -> dates ? "the same date as" : "the same as";
            case "!=" -> "different from";
            case ">=" -> dates ? "on or after" : "at least";
            default -> dates ? "after" : "greater than";
        };
    }

    // ---------------------------------------------------------------- config

    private static JsonNode readConfig(String json) throws BadConfig {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || node.isNull() || node.isMissingNode()) {
                return JSON.createObjectNode();
            }
            if (!node.isObject()) {
                throw new BadConfig("unreadable");
            }
            return node;
        } catch (IOException e) {
            throw new BadConfig("unreadable");
        }
    }

    private static String text(JsonNode config, String name) throws BadConfig {
        JsonNode node = config.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isValueNode()) {
            throw new BadConfig("has an unreadable " + name);
        }
        return node.asText();
    }

    private static BigDecimal number(JsonNode config, String name) throws BadConfig {
        JsonNode node = config.get(name);
        if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        BigDecimal parsed = node.isTextual() ? FieldValueFormatter.parseNumber(node.asText()) : null;
        if (parsed == null) {
            throw new BadConfig("has a " + name + " that is not a number");
        }
        return parsed;
    }

    /** A date bound: ISO, {@code today} or {@code ±N(d|w|m|y)} from today. */
    private static LocalDate dateBound(JsonNode config, String name, LocalDate today) throws BadConfig {
        String text = text(config, name);
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        if (t.equalsIgnoreCase("today")) {
            return today;
        }
        LocalDate iso = FieldValueFormatter.parseIsoDate(t);
        if (iso != null) {
            return iso;
        }
        Matcher m = RELATIVE_DATE.matcher(t);
        if (!m.matches()) {
            throw new BadConfig("has a " + name + " that is not a date");
        }
        long amount = Long.parseLong(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
        return switch (Character.toLowerCase(m.group(3).charAt(0))) {
            case 'd' -> today.plusDays(amount);
            case 'w' -> today.plusWeeks(amount);
            case 'm' -> today.plusMonths(amount);
            default -> today.plusYears(amount);
        };
    }

    // --------------------------------------------------------------- helpers

    private static boolean isWarning(DocFieldRule rule) {
        return rule.getSeverity() != null && "WARNING".equalsIgnoreCase(rule.getSeverity().strip());
    }

    private static boolean isPhoto(RegionKind kind) {
        return kind == RegionKind.IMAGE || kind == RegionKind.SIGNATURE;
    }

    private static boolean isChoice(RegionKind kind) {
        return kind == RegionKind.CHOICE;
    }

    private static String kindPhrase(RegionKind kind) {
        return switch (kind) {
            case IMAGE -> "a photo field";
            case SIGNATURE -> "a signature field";
            case CHECKBOX -> "a checkbox field";
            default -> "a " + kind.name().toLowerCase(Locale.ROOT) + " field";
        };
    }

    private static String show(LocalDate date, RegionView region) {
        String pattern = region.props().dateFormat();
        return pattern == null ? date.toString() : FieldValueFormatter.formatDate(pattern, date.toString());
    }

    private static String plain(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
