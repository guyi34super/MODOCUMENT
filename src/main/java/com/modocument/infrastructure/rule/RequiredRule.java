package com.modocument.infrastructure.rule;

import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Component;

@Component
public class RequiredRule implements ValidationRuleValidator {

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.REQUIRED;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        if (rawValue == null || rawValue.isBlank()) {
            return new ValidationError(row, fieldName, rawValue, "Value is required.");
        }
        return null;
    }
}
