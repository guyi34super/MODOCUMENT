package com.modocument.infrastructure.persistence;

import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.entity.ValidationRuleId;
import com.modocument.domain.model.RuleType;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ValidationRuleRepositoryImpl implements ValidationRuleRepository {

    private final ValidationRuleJpaRepository jpa;

    public ValidationRuleRepositoryImpl(ValidationRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<ValidationRule> getRulesByTemplate(int templateId) {
        return jpa.findByTemplateId(templateId);
    }

    @Override
    public ValidationRule save(ValidationRule rule) {
        return jpa.save(rule);
    }

    @Override
    public boolean delete(int templateId, String fieldName, RuleType ruleType) {
        ValidationRuleId id = new ValidationRuleId(templateId, fieldName, ruleType);
        if (!jpa.existsById(id)) {
            return false;
        }
        jpa.deleteById(id);
        return true;
    }
}
