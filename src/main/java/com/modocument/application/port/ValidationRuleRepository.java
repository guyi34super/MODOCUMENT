package com.modocument.application.port;

import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;

import java.util.List;

public interface ValidationRuleRepository {

    List<ValidationRule> getRulesByTemplate(int templateId);

    /** Insert-or-update on the composite key (TemplateId, FieldName, RuleType). */
    ValidationRule save(ValidationRule rule);

    /** Returns true when a rule existed and was removed. */
    boolean delete(int templateId, String fieldName, RuleType ruleType);
}
