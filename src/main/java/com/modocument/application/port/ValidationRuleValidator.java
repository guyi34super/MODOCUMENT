package com.modocument.application.port;

import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;

/** Strategy interface implemented by each validation rule of the rule engine. */
public interface ValidationRuleValidator {

    boolean canHandle(RuleType type);

    /** Returns a {@link ValidationError} describing the failure, or null when valid. */
    ValidationError validate(int row, String fieldName, String rawValue, String configJson);
}
