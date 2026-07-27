package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validation engine core: loads the rules configured for a template, runs each
 * row through the matching validators and keeps only the first error per field.
 * Emits one {@link RowValidationResult} per field per row (errorMessage null
 * when the field is valid).
 */
@Service
public class ValidateRowsUseCase {

    private final ValidationRuleRepository repository;
    private final List<ValidationRuleValidator> validators;

    public ValidateRowsUseCase(ValidationRuleRepository repository,
                               List<ValidationRuleValidator> validators) {
        this.repository = repository;
        this.validators = validators;
    }

    public List<RowValidationResult> handle(ValidationRequest request) {
        List<ValidationRule> rules = repository.getRulesByTemplate(request.getTemplateId());
        List<RowValidationResult> results = new ArrayList<>();

        Map<String, List<ValidationRule>> groupedRules = new LinkedHashMap<>();
        for (ValidationRule rule : rules) {
            groupedRules.computeIfAbsent(rule.getFieldName(), k -> new ArrayList<>()).add(rule);
        }

        for (int i = 0; i < request.getRows().size(); i++) {
            int rowNum = i + 1;
            Map<String, String> row = request.getRows().get(i);

            for (Map.Entry<String, List<ValidationRule>> fieldRules : groupedRules.entrySet()) {
                String fieldName = fieldRules.getKey();
                String rawValue = row.getOrDefault(fieldName, "");
                if (rawValue == null) {
                    rawValue = "";
                }

                String firstError = null;
                String firstRuleType = null;

                for (ValidationRule rule : fieldRules.getValue()) {
                    ValidationRuleValidator validator = findValidator(rule);
                    if (validator == null) {
                        continue;
                    }

                    ValidationError error =
                            validator.validate(rowNum, fieldName, rawValue, rule.getConfigJson());
                    if (error != null) {
                        firstError = error.errorMessage();
                        firstRuleType = rule.getRuleType().name();
                        break; // Only keep first error for this field
                    }
                }

                results.add(new RowValidationResult(rowNum, fieldName, rawValue, firstError, firstRuleType));
            }
        }

        return results;
    }

    private ValidationRuleValidator findValidator(ValidationRule rule) {
        return validators.stream()
                .filter(v -> v.canHandle(rule.getRuleType()))
                .findFirst()
                .orElse(null);
    }
}
