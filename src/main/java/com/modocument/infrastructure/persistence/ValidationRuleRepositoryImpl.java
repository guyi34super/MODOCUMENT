package com.modocument.infrastructure.persistence;

import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.ValidationRule;
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
}
