package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.CreateTemplateFieldDto;
import com.modocument.application.dto.CreateTemplateRequest;
import com.modocument.application.dto.CreateTemplateResponse;
import com.modocument.application.dto.CreateTemplateRuleDto;
import com.modocument.application.dto.TemplateValidationError;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.application.validation.TemplateDefinitionException;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Creates a custom template together with its fields and their validation
 * rules (including UI-supplied regex patterns) in one transaction. The whole
 * definition is validated up front; nothing is persisted when any part of it
 * is invalid.
 */
@Service
public class CreateTemplateWithFieldsUseCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository templateRepository;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final TemplateDataTypeRepository templateDataTypeRepository;
    private final ValidationRuleRepository validationRuleRepository;

    public CreateTemplateWithFieldsUseCase(TemplateRepository templateRepository,
                                           TemplateFieldsRepository templateFieldsRepository,
                                           TemplateDataTypeRepository templateDataTypeRepository,
                                           ValidationRuleRepository validationRuleRepository) {
        this.templateRepository = templateRepository;
        this.templateFieldsRepository = templateFieldsRepository;
        this.templateDataTypeRepository = templateDataTypeRepository;
        this.validationRuleRepository = validationRuleRepository;
    }

    @Transactional
    public CreateTemplateResponse handle(CreateTemplateRequest request) {
        List<TemplateDataType> dataTypes = templateDataTypeRepository.list();
        List<TemplateValidationError> errors = validate(request, dataTypes);
        if (!errors.isEmpty()) {
            throw new TemplateDefinitionException(errors);
        }

        Template template = new Template(0, request.getName().trim(), request.getDescription());
        templateRepository.add(template);

        List<CreateTemplateResponse.CreatedField> createdFields = new ArrayList<>();
        for (CreateTemplateFieldDto fieldDto : request.getFields()) {
            TemplateDataType dataType = resolveDataType(fieldDto.getDataType(), dataTypes).orElseThrow();

            TemplateFields field = new TemplateFields();
            field.setTemplateId(template.getTemplateId());
            field.setFieldName(fieldDto.getFieldName().trim());
            field.setTemplateDataTypeId(dataType.getTemplateDataTypeId());
            templateFieldsRepository.add(field);

            List<CreateTemplateResponse.CreatedRule> createdRules = new ArrayList<>();
            for (CreateTemplateRuleDto ruleDto : rulesOf(fieldDto)) {
                RuleType ruleType = parseRuleType(ruleDto.getRuleType()).orElseThrow();
                String configJson = toConfigJson(ruleDto.getConfig());
                validationRuleRepository.save(new ValidationRule(
                        template.getTemplateId(), field.getFieldName(), ruleType, configJson));
                createdRules.add(new CreateTemplateResponse.CreatedRule(ruleType.name(), configJson));
            }

            createdFields.add(new CreateTemplateResponse.CreatedField(
                    field.getTemplateFieldsId(), field.getFieldName(),
                    dataType.getTemplateDataTypeId(), dataType.getDataType(), createdRules));
        }

        return new CreateTemplateResponse(
                template.getTemplateId(), template.getName(), template.getDescription(), createdFields);
    }

    private List<TemplateValidationError> validate(CreateTemplateRequest request,
                                                   List<TemplateDataType> dataTypes) {
        List<TemplateValidationError> errors = new ArrayList<>();

        if (request.getName() == null || request.getName().isBlank()) {
            errors.add(new TemplateValidationError(null, null, "Template name is required."));
        }
        if (request.getFields() == null || request.getFields().isEmpty()) {
            errors.add(new TemplateValidationError(null, null, "At least one field is required."));
            return errors;
        }

        Set<String> seenNames = new HashSet<>();
        for (CreateTemplateFieldDto field : request.getFields()) {
            String fieldName = field.getFieldName() == null ? "" : field.getFieldName().trim();
            if (fieldName.isEmpty()) {
                errors.add(new TemplateValidationError(null, null, "Field name is required."));
                continue;
            }
            if (!seenNames.add(fieldName.toLowerCase(Locale.ROOT))) {
                errors.add(new TemplateValidationError(fieldName, null,
                        "Duplicate field name '" + fieldName + "'."));
            }
            if (resolveDataType(field.getDataType(), dataTypes).isEmpty()) {
                errors.add(new TemplateValidationError(fieldName, null,
                        "Unknown data type '" + field.getDataType() + "'."));
            }

            for (CreateTemplateRuleDto rule : rulesOf(field)) {
                Optional<RuleType> ruleType = parseRuleType(rule.getRuleType());
                if (ruleType.isEmpty()) {
                    errors.add(new TemplateValidationError(fieldName, rule.getRuleType(),
                            "Unknown rule type '" + rule.getRuleType() + "'."));
                    continue;
                }
                if (ruleType.get() == RuleType.REGEX) {
                    Object pattern = rule.getConfig() == null ? null : rule.getConfig().get("Pattern");
                    String problem = RegexPatternChecker.check(pattern == null ? null : pattern.toString());
                    if (problem != null) {
                        errors.add(new TemplateValidationError(fieldName, RuleType.REGEX.name(), problem));
                    }
                }
            }
        }
        return errors;
    }

    private static List<CreateTemplateRuleDto> rulesOf(CreateTemplateFieldDto field) {
        return field.getRules() == null ? List.of() : field.getRules();
    }

    /** Accepts the data type name (case-insensitive) or its numeric id. */
    private static Optional<TemplateDataType> resolveDataType(String value,
                                                              List<TemplateDataType> dataTypes) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String wanted = value.trim();
        return dataTypes.stream()
                .filter(dt -> wanted.equalsIgnoreCase(dt.getDataType())
                        || wanted.equals(String.valueOf(dt.getTemplateDataTypeId())))
                .findFirst();
    }

    /** Rule types travel by enum name only; ordinals are a persistence detail. */
    public static Optional<RuleType> parseRuleType(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RuleType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String toConfigJson(Map<String, Object> config) {
        try {
            return MAPPER.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Rule config cannot be serialized to JSON.", e);
        }
    }
}
