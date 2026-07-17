package com.modocument.domain.entity;

import com.modocument.domain.model.RuleType;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link ValidationRule}: TemplateId + FieldName + RuleType. */
public class ValidationRuleId implements Serializable {

    private int templateId;
    private String fieldName;
    private RuleType ruleType;

    public ValidationRuleId() {
    }

    public ValidationRuleId(int templateId, String fieldName, RuleType ruleType) {
        this.templateId = templateId;
        this.fieldName = fieldName;
        this.ruleType = ruleType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidationRuleId that)) {
            return false;
        }
        return templateId == that.templateId
                && Objects.equals(fieldName, that.fieldName)
                && ruleType == that.ruleType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, fieldName, ruleType);
    }
}
