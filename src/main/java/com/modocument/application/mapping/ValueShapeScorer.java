package com.modocument.application.mapping;

import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Content-based mapping signal: how well a column's sample values fit a
 * template field's expectations. The field's own validation rules are the
 * strongest classifier (an email regex rule IS an email detector); when a
 * field has no rules, its data type provides a weak fallback. All scoring is
 * local — no sample value ever leaves the backend.
 */
@Service
public class ValueShapeScorer {

    /** Minimum non-blank samples before shape says anything at all. */
    static final int MIN_SAMPLES = 3;

    private static final List<Pattern> DATE_SHAPES = List.of(
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}"),
            Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}"),
            Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{4}"));

    private final ValidationRuleRepository rules;
    private final List<ValidationRuleValidator> validators;

    public ValueShapeScorer(ValidationRuleRepository rules,
                            List<ValidationRuleValidator> validators) {
        this.rules = rules;
        this.validators = validators;
    }

    /** Rules for the template grouped by field name — fetched once per mapping run. */
    public Map<String, List<ValidationRule>> rulesByField(int templateId) {
        return rules.getRulesByTemplate(templateId).stream()
                .collect(Collectors.groupingBy(ValidationRule::getFieldName));
    }

    /**
     * Fraction of samples that fit the field, or empty when there is no
     * usable signal (too few samples, no scorable rules and Text data type).
     */
    public OptionalDouble score(TemplateFields field, List<ValidationRule> fieldRules,
                                List<String> samples) {
        if (samples == null || samples.size() < MIN_SAMPLES) {
            return OptionalDouble.empty();
        }

        List<ValidationRule> scorable = fieldRules == null ? List.of()
                : fieldRules.stream()
                        // REQUIRED rejects blanks, not shapes — no signal here.
                        .filter(rule -> rule.getRuleType() != RuleType.REQUIRED)
                        .filter(rule -> findValidator(rule.getRuleType()) != null)
                        .toList();

        if (!scorable.isEmpty()) {
            int passes = 0;
            int judged = 0;
            for (String sample : samples) {
                Boolean pass = passesAll(sample, scorable, field.getFieldName());
                if (pass != null) {
                    judged++;
                    if (pass) {
                        passes++;
                    }
                }
            }
            // Score over the judged samples only, and require the same minimum
            // there — a handful of technically-evaluated values must not stand
            // in for a full sample.
            return judged >= MIN_SAMPLES
                    ? OptionalDouble.of((double) passes / judged)
                    : OptionalDouble.empty();
        }

        return dataTypeScore(field, samples);
    }

    /**
     * TRUE only when every scorable rule actually ran and accepted the value;
     * null when any rule could not be evaluated, so a partially-checked
     * sample is never counted as a clean pass.
     */
    private Boolean passesAll(String sample, List<ValidationRule> scorable, String fieldName) {
        for (ValidationRule rule : scorable) {
            ValidationRuleValidator validator = findValidator(rule.getRuleType());
            try {
                // RangeRule throws on bad config and RegexRule's deadline
                // guard can throw mid-match — a broken rule is no signal,
                // never a veto over the whole mapping stage.
                if (validator.validate(0, fieldName, sample, rule.getConfigJson()) != null) {
                    return false;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return Boolean.TRUE;
    }

    private OptionalDouble dataTypeScore(TemplateFields field, List<String> samples) {
        String dataType = field.getTemplateDataType() == null
                ? null
                : field.getTemplateDataType().getDataType();
        if ("Number".equalsIgnoreCase(dataType)) {
            return OptionalDouble.of(fraction(samples, ValueShapeScorer::isNumeric));
        }
        if ("Date".equalsIgnoreCase(dataType)) {
            return OptionalDouble.of(fraction(samples, ValueShapeScorer::isDateShaped));
        }
        // Text (or unknown type) matches anything — that is no signal at all.
        return OptionalDouble.empty();
    }

    private static double fraction(List<String> samples, java.util.function.Predicate<String> test) {
        long passes = samples.stream().filter(test).count();
        return (double) passes / samples.size();
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDateShaped(String value) {
        String trimmed = value.trim();
        return DATE_SHAPES.stream().anyMatch(p -> p.matcher(trimmed).matches());
    }

    private ValidationRuleValidator findValidator(RuleType type) {
        return validators.stream().filter(v -> v.canHandle(type)).findFirst().orElse(null);
    }
}
