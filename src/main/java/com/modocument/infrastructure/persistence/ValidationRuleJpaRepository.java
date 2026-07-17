package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.entity.ValidationRuleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValidationRuleJpaRepository extends JpaRepository<ValidationRule, ValidationRuleId> {

    List<ValidationRule> findByTemplateId(int templateId);
}
