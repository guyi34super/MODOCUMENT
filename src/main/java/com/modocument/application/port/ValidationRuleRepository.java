package com.modocument.application.port;

import com.modocument.domain.entity.ValidationRule;

import java.util.List;

public interface ValidationRuleRepository {

    List<ValidationRule> getRulesByTemplate(int templateId);
}
