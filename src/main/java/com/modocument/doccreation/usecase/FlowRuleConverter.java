package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.application.validation.RegexValidationErrorMessages;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;

import java.math.BigDecimal;

/**
 * Converts one flow-template field rule ({@code ValidationRules}) into a
 * document-creation field rule: the conversion table of
 * docs/doccreation/field-validation-contract.md §3 ("Importing from a flow
 * template"), shared by the PDF template import ({@link DocFieldRuleUseCase})
 * and the copy of a flow template into a data template
 * (data-templates-contract.md §2, {@link DataTemplateUseCase}).
 */
public final class FlowRuleConverter {

    /**
     * The flow UI's EMAIL preset ({@code EMAIL_PATTERN} in
     * frontend/src/api/validationRules.js): a flow REGEX rule with exactly
     * this pattern becomes an EMAIL rule.
     */
    public static final String FLOW_EMAIL_PATTERN = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    /** The reason a flow rule type without a counterpart is skipped. */
    static final String CANNOT_IMPORT = "can't be imported";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private FlowRuleConverter() {
    }

    /** One flow rule converted to a field rule: its type, normalised config and message (null = default). */
    public record Converted(DocRuleType type, ObjectNode config, String message) {
    }

    /**
     * One flow rule as a field rule (contract §3 conversion table).
     *
     * @throws IllegalArgumentException with the reason it is skipped
     */
    static Converted convert(ValidationRule flowRule) {
        RuleType type = flowRule.getRuleType();
        if (type == null) {
            throw new IllegalArgumentException(CANNOT_IMPORT);
        }
        ObjectNode config = FieldRuleChecks.readConfig(flowRule.getConfigJson());
        return switch (type) {
            case REQUIRED -> new Converted(DocRuleType.REQUIRED, NODES.objectNode(), null);
            case EXPIRY_DATE -> new Converted(DocRuleType.EXPIRY_DATE, NODES.objectNode(), null);
            case DATE_FORMAT -> {
                ObjectNode out = NODES.objectNode();
                out.putArray("Formats").add("yyyy-MM-dd");
                yield new Converted(DocRuleType.DATE_FORMAT, out, null);
            }
            case REGEX -> {
                JsonNode patternNode = config.get("Pattern");
                String pattern = patternNode == null || !patternNode.isValueNode() ? null : patternNode.asText();
                if (RegexPatternChecker.check(pattern) != null) {
                    throw new IllegalArgumentException("its pattern is not valid");
                }
                if (FLOW_EMAIL_PATTERN.equals(pattern)) {
                    yield new Converted(DocRuleType.EMAIL, NODES.objectNode(), null);
                }
                ObjectNode out = NODES.objectNode();
                out.put("Pattern", pattern);
                yield new Converted(DocRuleType.REGEX, out, regexMessage(flowRule.getFieldName(), pattern));
            }
            case RANGE -> {
                BigDecimal min = decimal(config.get("Min"));
                BigDecimal max = decimal(config.get("Max"));
                if (min == null && max == null || min != null && max != null && min.compareTo(max) > 0) {
                    throw new IllegalArgumentException("its range is not valid");
                }
                ObjectNode out = NODES.objectNode();
                if (min != null) {
                    out.put("Min", min);
                }
                if (max != null) {
                    out.put("Max", max);
                }
                yield new Converted(DocRuleType.RANGE, out, null);
            }
            case CONDITIONAL_REQUIREMENT, TYPE, CUSTOM -> throw new IllegalArgumentException(CANNOT_IMPORT);
        };
    }

    /**
     * The flow's own message for a REGEX rule of {@code sourceFieldName}
     * (the Dayforce/PowerPay wording), or null when it would only be the
     * generic "Validation error for X." text.
     */
    static String regexMessage(String sourceFieldName, String pattern) {
        if (sourceFieldName == null) {
            return null;
        }
        String message = RegexValidationErrorMessages
                .getRegexValidationErrorMessage(0, sourceFieldName, null, pattern).errorMessage();
        if (message == null || message.startsWith("Validation error for " + sourceFieldName + ".")) {
            return null;
        }
        String clean = message.strip();
        return clean.length() <= FieldRuleChecks.MAX_MESSAGE ? clean
                : clean.substring(0, FieldRuleChecks.MAX_MESSAGE - 1).strip() + "…";
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("its range is not valid");
        }
    }
}
