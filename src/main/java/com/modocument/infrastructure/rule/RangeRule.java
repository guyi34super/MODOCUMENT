package com.modocument.infrastructure.rule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Component;

@Component
public class RangeRule implements ValidationRuleValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Config(@JsonProperty("Min") double min, @JsonProperty("Max") double max) {
    }

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.RANGE;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        Config cfg;
        try {
            cfg = MAPPER.readValue(configJson, Config.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Range rule configuration: " + configJson, e);
        }
        String example = exampleWithin(cfg.min(), cfg.max());

        double value;
        try {
            value = Double.parseDouble(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            return new ValidationError(row, fieldName, rawValue,
                    "Value is not a number, e.g. " + example + ".");
        }

        if (value < cfg.min() || value > cfg.max()) {
            return new ValidationError(row, fieldName, rawValue,
                    "Must be between " + formatBound(cfg.min()) + " and " + formatBound(cfg.max())
                            + ", e.g. " + example + ".");
        }
        return null;
    }

    /**
     * A concrete acceptable value, so the fix is obvious. The midpoint is
     * rounded down only when that keeps it inside the range — with Min=0.1 and
     * Max=0.4 flooring produced "e.g. 0", an example the rule itself rejects.
     */
    private static String exampleWithin(double min, double max) {
        double midpoint = (min + max) / 2;
        double rounded = Math.floor(midpoint);
        return formatBound(rounded >= min && rounded <= max ? rounded : midpoint);
    }

    /** Render whole-number bounds without a trailing ".0", matching the original message text. */
    private static String formatBound(double bound) {
        if (bound == Math.rint(bound) && !Double.isInfinite(bound)) {
            return String.valueOf((long) bound);
        }
        return String.valueOf(bound);
    }
}
