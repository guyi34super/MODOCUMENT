package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.doccreation.dto.RuleInput;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.doccreation.rules.RuleTypeCatalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks a template's field rules must pass when they are saved
 * (docs/doccreation/field-validation-contract.md §3, "Checks when rules are
 * saved"), for a PDF template (region keys) and, through a {@link Scope},
 * for a data template (field names and kinds, data-templates-contract.md §2). Every refusal is an {@link IllegalArgumentException} (400) that
 * names the rule; messages never contain a document value. The config of a
 * rule is normalised: only the keys of its type are kept (case-sensitive),
 * numbers and dates are checked and written in one form.
 */
final class FieldRuleChecks {

    static final int MAX_RULES_PER_FIELD = 50;
    static final int MAX_RULES = 2000;
    static final int MAX_MESSAGE = 300;
    static final int MAX_CONFIG_BYTES = 4096;
    static final int MAX_VALUES = 200;
    static final int MAX_VALUE_LENGTH = 200;
    static final int MAX_FORMATS = 20;
    static final int MAX_FORMAT_LENGTH = 64;
    static final int MAX_SOURCE_FIELD = 255;
    static final List<String> OPERATORS = List.of("<", "<=", "=", "!=", ">=", ">");
    static final Set<String> SEVERITIES = Set.of("ERROR", "WARNING");
    static final Set<String> ORIGINS = Set.of(DocFieldRuleUseCase.ORIGIN_MANUAL, DocFieldRuleUseCase.ORIGIN_IMPORTED,
            DocFieldRuleUseCase.ORIGIN_REGION_FLAG);
    /** A data template's rules are written by hand or copied from a flow template (the V330 CHECK). */
    static final Set<String> DATA_ORIGINS = Set.of(DocFieldRuleUseCase.ORIGIN_MANUAL,
            DocFieldRuleUseCase.ORIGIN_IMPORTED);

    /** ISO dates, {@code today}, or a relative date such as {@code -18y}, {@code +30d}, {@code +2w}, {@code -1m}. */
    private static final Pattern RELATIVE_DATE = Pattern.compile("([+-])(\\d{1,4})([dwmy])");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private FieldRuleChecks() {
    }

    /**
     * A rule to check, whichever store it is for: a PDF template's rule
     * ({@code key} = region key) or a data template's ({@code key} = field
     * name, data-templates-contract §2).
     */
    record Input(String key, String ruleType, JsonNode config, String message, String severity, String origin,
                 Integer sourceTemplateId, String sourceFieldName) {

        static Input of(RuleInput input) {
            return input == null ? null : new Input(input.regionKey(), input.ruleType(), input.config(),
                    input.message(), input.severity(), input.origin(), input.sourceTemplateId(),
                    input.sourceFieldName());
        }
    }

    /** One checked rule, not yet stored; {@code sortOrder} = position among the rules of the same field. */
    record Checked(String key, String ruleType, String configJson, String message, String severity, String origin,
                   Integer sourceTemplateId, String sourceFieldName, int sortOrder) {
    }

    /**
     * What a rule set is checked against: the field keys, their kinds (null
     * = kinds are not checked, as for a PDF template, whose rules on a kind
     * they do not fit stay as setup warnings), the origins it may hold and
     * the wording of the refusals.
     */
    record Scope(Set<String> keys, Map<String, RegionKind> kinds, Set<String> origins, String originWords,
                 String owner, String place) {

        /** A PDF template: the region keys of its current version (field-validation-contract §3). */
        static Scope blueprint(Set<String> keys) {
            return new Scope(keys, null, ORIGINS, "MANUAL, IMPORTED or REGION_FLAG", "A template",
                    "the template's current version");
        }

        /** A data template: its field names and their kinds (data-templates-contract §2). */
        static Scope dataTemplate(Map<String, RegionKind> kinds) {
            return new Scope(kinds.keySet(), kinds, DATA_ORIGINS, "MANUAL or IMPORTED", "A data template",
                    "the data template");
        }
    }

    /**
     * The checked rules (not yet stored: no id, bank or blueprint), sort
     * order = position among the rules of the same field.
     *
     * @param keys the region keys of the blueprint's current version
     */
    static List<DocFieldRule> check(List<RuleInput> inputs, Set<String> keys) {
        List<Input> list = inputs == null ? null : inputs.stream().map(Input::of).toList();
        List<DocFieldRule> out = new ArrayList<>();
        for (Checked checked : checkAll(list, Scope.blueprint(keys))) {
            DocFieldRule rule = toRule(checked);
            rule.setSortOrder(checked.sortOrder());
            out.add(rule);
        }
        return out;
    }

    /** {@link #check} for any {@link Scope}. */
    static List<Checked> checkAll(List<Input> inputs, Scope scope) {
        List<Input> list = inputs == null ? List.of() : inputs;
        if (list.size() > MAX_RULES) {
            throw new IllegalArgumentException(scope.owner() + " has at most " + MAX_RULES + " field rules.");
        }
        List<Checked> out = new ArrayList<>();
        Map<String, Integer> perField = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Checked rule = checkOne(i + 1, list.get(i), scope);
            int position = perField.merge(rule.key(), 1, Integer::sum);
            if (position > MAX_RULES_PER_FIELD) {
                throw new IllegalArgumentException("Field \"" + rule.key() + "\" has more than "
                        + MAX_RULES_PER_FIELD + " rules.");
            }
            out.add(new Checked(rule.key(), rule.ruleType(), rule.configJson(), rule.message(), rule.severity(),
                    rule.origin(), rule.sourceTemplateId(), rule.sourceFieldName(), position - 1));
        }
        return out;
    }

    /** The per-field and per-template limits of a rule set built elsewhere (an import). */
    static void checkLimits(List<DocFieldRule> rules) {
        checkLimits(rules.stream().map(DocFieldRule::getRegionKey).toList(), "A template");
    }

    /** The per-field and per-template limits, given the field key of every rule. */
    static void checkLimits(List<String> ruleKeys, String owner) {
        if (ruleKeys.size() > MAX_RULES) {
            throw new IllegalArgumentException(owner + " has at most " + MAX_RULES + " field rules.");
        }
        Map<String, Integer> perField = new HashMap<>();
        for (String key : ruleKeys) {
            if (perField.merge(key, 1, Integer::sum) > MAX_RULES_PER_FIELD) {
                throw new IllegalArgumentException("Field \"" + key + "\" would have more than "
                        + MAX_RULES_PER_FIELD + " rules.");
            }
        }
    }

    static Checked checkOne(int number, Input input, Scope scope) {
        if (input == null) {
            throw new IllegalArgumentException("Rule " + number + " is empty.");
        }
        String key = input.key() == null ? "" : input.key().strip();
        DocRuleType type = DocRuleType.parse(input.ruleType()).orElseThrow(() -> new IllegalArgumentException(
                "Rule " + number + ": unknown rule type \"" + truncate(input.ruleType(), 40) + "\"."));
        String where = "Rule " + number + " (field \"" + truncate(key, 64) + "\", " + type.name() + "): ";
        if (!scope.keys().contains(key)) {
            throw new IllegalArgumentException(where + "the field is not in " + scope.place() + ".");
        }
        if (scope.kinds() != null) {
            RegionKind kind = scope.kinds().get(key);
            if (!RuleTypeCatalog.fits(type, kind)) {
                throw new IllegalArgumentException(where + "this rule does not apply to " + kindWords(kind)
                        + " field.");
            }
        }
        ObjectNode config = config(type, input.config(), key, scope, where);
        String configJson = config.toString();
        if (configJson.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException(where + "the settings are larger than 4 kB.");
        }
        String message = input.message() == null || input.message().isBlank() ? null : input.message().strip();
        if (message != null && message.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException(where + "the message is limited to " + MAX_MESSAGE + " characters.");
        }
        String severity = input.severity() == null || input.severity().isBlank() ? "ERROR"
                : input.severity().strip().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException(where + "the severity must be ERROR or WARNING.");
        }
        String origin = input.origin() == null || input.origin().isBlank() ? DocFieldRuleUseCase.ORIGIN_MANUAL
                : input.origin().strip().toUpperCase(Locale.ROOT);
        if (!scope.origins().contains(origin)) {
            throw new IllegalArgumentException(where + "the origin must be " + scope.originWords() + ".");
        }
        String sourceField = input.sourceFieldName() == null || input.sourceFieldName().isBlank() ? null
                : input.sourceFieldName().strip();
        if (sourceField != null && sourceField.length() > MAX_SOURCE_FIELD) {
            throw new IllegalArgumentException(where + "the source field name is too long.");
        }
        return new Checked(key, type.name(), configJson, message, severity, origin, input.sourceTemplateId(),
                sourceField, 0);
    }

    private static DocFieldRule toRule(Checked checked) {
        DocFieldRule rule = new DocFieldRule();
        rule.setRegionKey(checked.key());
        rule.setRuleType(checked.ruleType());
        rule.setConfigJson(checked.configJson());
        rule.setMessage(checked.message());
        rule.setSeverity(checked.severity());
        rule.setOrigin(checked.origin());
        rule.setSourceTemplateId(checked.sourceTemplateId());
        rule.setSourceFieldName(checked.sourceFieldName());
        return rule;
    }

    /** A kind in words with its article: "a number", "an image". */
    static String kindWords(RegionKind kind) {
        String name = kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT);
        return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    /** The normalised config of one rule; only the keys of its type are kept. */
    static ObjectNode config(DocRuleType type, JsonNode raw, String key, Scope scope, String where) {
        JsonNode in = raw == null || raw.isNull() ? NODES.objectNode() : raw;
        if (!in.isObject()) {
            throw new IllegalArgumentException(where + "the settings must be a JSON object.");
        }
        ObjectNode out = NODES.objectNode();
        switch (type) {
            case REQUIRED, EMAIL, EXPIRY_DATE -> {
                // no settings
            }
            case REGEX -> {
                String pattern = text(in.get("Pattern"));
                String problem = RegexPatternChecker.check(pattern);
                if (problem != null) {
                    throw new IllegalArgumentException(where + problem);
                }
                out.put("Pattern", pattern);
            }
            case LENGTH -> {
                Integer min = length(in.get("Min"), where + "Min");
                Integer max = length(in.get("Max"), where + "Max");
                requireOne(min, max, where);
                if (min != null && max != null && min > max) {
                    throw new IllegalArgumentException(where + "Min must not be greater than Max.");
                }
                putIfSet(out, "Min", min);
                putIfSet(out, "Max", max);
            }
            case RANGE -> {
                BigDecimal min = number(in.get("Min"), where + "Min");
                BigDecimal max = number(in.get("Max"), where + "Max");
                requireOne(min, max, where);
                if (min != null && max != null && min.compareTo(max) > 0) {
                    throw new IllegalArgumentException(where + "Min must not be greater than Max.");
                }
                if (min != null) {
                    out.put("Min", min);
                }
                if (max != null) {
                    out.put("Max", max);
                }
            }
            case ALLOWED_VALUES -> {
                JsonNode values = in.get("Values");
                if (values == null || !values.isArray() || values.isEmpty()) {
                    throw new IllegalArgumentException(where + "Values needs at least one value.");
                }
                if (values.size() > MAX_VALUES) {
                    throw new IllegalArgumentException(where + "Values has at most " + MAX_VALUES + " entries.");
                }
                ArrayNode list = out.putArray("Values");
                for (JsonNode value : values) {
                    if (value == null || !value.isValueNode() || value.isNull()) {
                        throw new IllegalArgumentException(where + "each value must be a text.");
                    }
                    String text = value.asText();
                    if (text.length() > MAX_VALUE_LENGTH) {
                        throw new IllegalArgumentException(where + "each value is limited to " + MAX_VALUE_LENGTH
                                + " characters.");
                    }
                    list.add(text);
                }
                JsonNode ignoreCase = in.get("IgnoreCase");
                if (ignoreCase != null && !ignoreCase.isNull()) {
                    if (!ignoreCase.isBoolean()) {
                        throw new IllegalArgumentException(where + "IgnoreCase must be true or false.");
                    }
                    out.put("IgnoreCase", ignoreCase.booleanValue());
                }
            }
            case DATE_FORMAT -> {
                JsonNode formats = in.get("Formats");
                if (formats != null && !formats.isNull()) {
                    if (!formats.isArray()) {
                        throw new IllegalArgumentException(where + "Formats must be a list of date patterns.");
                    }
                    if (formats.size() > MAX_FORMATS) {
                        throw new IllegalArgumentException(where + "Formats has at most " + MAX_FORMATS
                                + " patterns.");
                    }
                    Set<String> clean = new LinkedHashSet<>();
                    for (JsonNode format : formats) {
                        clean.add(dateFormat(format, where));
                    }
                    if (!clean.isEmpty()) {
                        ArrayNode list = out.putArray("Formats");
                        clean.forEach(list::add);
                    }
                }
            }
            case DATE_RANGE -> {
                String min = dateExpression(in.get("Min"), where + "Min");
                String max = dateExpression(in.get("Max"), where + "Max");
                requireOne(min, max, where);
                if (min != null && max != null) {
                    LocalDate today = LocalDate.now();
                    if (resolve(min, today).isAfter(resolve(max, today))) {
                        throw new IllegalArgumentException(where + "Min must not be after Max.");
                    }
                }
                putIfSet(out, "Min", min);
                putIfSet(out, "Max", max);
            }
            case REQUIRED_IF -> {
                out.put("Field", otherField(in.get("Field"), key, scope, where));
                JsonNode equals = in.get("Equals");
                if (equals != null && !equals.isNull()) {
                    if (!equals.isValueNode()) {
                        throw new IllegalArgumentException(where + "Equals must be a text.");
                    }
                    String text = equals.asText();
                    if (text.length() > MAX_VALUE_LENGTH) {
                        throw new IllegalArgumentException(where + "Equals is limited to " + MAX_VALUE_LENGTH
                                + " characters.");
                    }
                    out.put("Equals", text);
                }
            }
            case COMPARE -> {
                out.put("Field", otherField(in.get("Field"), key, scope, where));
                String op = text(in.get("Op"));
                if (op == null || !OPERATORS.contains(op.strip())) {
                    throw new IllegalArgumentException(where + "Op must be one of " + String.join(" ", OPERATORS)
                            + ".");
                }
                out.put("Op", op.strip());
            }
            default -> throw new IllegalArgumentException(where + "unsupported rule type.");
        }
        return out;
    }

    /** Parses a stored config (an empty object when unreadable). */
    static ObjectNode readConfig(String json) {
        if (json == null || json.isBlank()) {
            return NODES.objectNode();
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node instanceof ObjectNode object ? object : NODES.objectNode();
        } catch (IOException e) {
            return NODES.objectNode();
        }
    }

    // ------------------------------------------------------------ helpers

    private static String otherField(JsonNode node, String key, Scope scope, String where) {
        String other = text(node);
        other = other == null ? null : other.strip();
        if (other == null || other.isEmpty()) {
            throw new IllegalArgumentException(where + "Field must name another field.");
        }
        if (other.equals(key)) {
            throw new IllegalArgumentException(where + "Field must name another field, not the field itself.");
        }
        if (!scope.keys().contains(other)) {
            throw new IllegalArgumentException(where + "Field \"" + truncate(other, 64)
                    + "\" is not in " + scope.place() + ".");
        }
        return other;
    }

    private static String dateFormat(JsonNode node, String where) {
        String format = text(node);
        if (format == null || format.isBlank() || format.length() > MAX_FORMAT_LENGTH) {
            throw new IllegalArgumentException(where + "each date format must have 1 to " + MAX_FORMAT_LENGTH
                    + " characters.");
        }
        try {
            DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "\"" + format + "\" is not a valid date format.");
        }
        return format;
    }

    private static Integer length(JsonNode node, String what) {
        if (node == null || node.isNull() || node.isTextual() && node.asText().isBlank()) {
            return null;
        }
        BigDecimal value = number(node, what);
        try {
            int whole = value.intValueExact();
            if (whole < 0) {
                throw new ArithmeticException();
            }
            return whole;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what + " must be a whole number of 0 or more.");
        }
    }

    private static BigDecimal number(JsonNode node, String what) {
        if (node == null || node.isNull() || node.isTextual() && node.asText().isBlank()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().strip().replace(",", ""));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException(what + " must be a number.");
    }

    /** An ISO date, {@code today} or a relative date; null when not set. */
    private static String dateExpression(JsonNode node, String what) {
        String value = text(node);
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        String lower = clean.toLowerCase(Locale.ROOT);
        if ("today".equals(lower) || RELATIVE_DATE.matcher(lower).matches()) {
            return lower;
        }
        try {
            LocalDate.parse(clean);
            return clean;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " must be a date (yyyy-MM-dd), today, or a relative date "
                    + "like -18y or +30d.");
        }
    }

    /** The date an expression of {@link #dateExpression} stands for, relative to {@code today}. */
    static LocalDate resolve(String expression, LocalDate today) {
        if ("today".equals(expression)) {
            return today;
        }
        Matcher m = RELATIVE_DATE.matcher(expression);
        if (m.matches()) {
            long amount = Long.parseLong(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
            ChronoUnit unit = switch (m.group(3)) {
                case "d" -> ChronoUnit.DAYS;
                case "w" -> ChronoUnit.WEEKS;
                case "m" -> ChronoUnit.MONTHS;
                default -> ChronoUnit.YEARS;
            };
            return today.plus(amount, unit);
        }
        return LocalDate.parse(expression);
    }

    private static void requireOne(Object min, Object max, String where) {
        if (min == null && max == null) {
            throw new IllegalArgumentException(where + "set Min, Max or both.");
        }
    }

    private static void putIfSet(ObjectNode out, String name, Integer value) {
        if (value != null) {
            out.put(name, value);
        }
    }

    private static void putIfSet(ObjectNode out, String name, String value) {
        if (value != null) {
            out.put(name, value);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isValueNode() ? null : node.asText();
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
