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
        double value;
        try {
            value = Double.parseDouble(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            return new ValidationError(row, fieldName, rawValue, "Value is not a number.");
        }

        Config cfg;
        try {
            cfg = MAPPER.readValue(configJson, Config.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Range rule configuration: " + configJson, e);
        }

        if (value < cfg.min() || value > cfg.max()) {
            return new ValidationError(row, fieldName, rawValue,
                    "Must be between " + formatBound(cfg.min()) + " and " + formatBound(cfg.max()) + ".");
        }
        return null;
    }

    /** Render whole-number bounds without a trailing ".0", matching the original message text. */
    private static String formatBound(double bound) {
        if (bound == Math.rint(bound) && !Double.isInfinite(bound)) {
            return String.valueOf((long) bound);
        }
        return String.valueOf(bound);
    }
}
