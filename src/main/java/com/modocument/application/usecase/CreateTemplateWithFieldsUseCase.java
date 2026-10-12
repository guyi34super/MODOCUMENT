package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.dto.CreateTemplateFieldDto;
import com.modocument.application.port.AuditRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import com.modocument.application.dto.CreateTemplateRequest;
import com.modocument.application.dto.CreateTemplateResponse;
import com.modocument.application.dto.CreateTemplateRuleDto;
import com.modocument.application.dto.TemplateVisibilityRequest;
import com.modocument.application.dto.TemplateValidationError;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.security.RolePermissions;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.application.validation.TemplateDefinitionException;
import com.modocument.application.validation.TemplateFieldHints;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.TemplateVisibility;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Creates a custom template together with its fields and their validation
 * rules (including UI-supplied regex patterns) in one transaction. The whole
 * definition is validated up front; nothing is persisted when any part of it
 * is invalid. ws22: the template may be created bank-shared
 * ({@code visibility: BANK}) by a template:publish holder (403 otherwise).
 */
@Service
public class CreateTemplateWithFieldsUseCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository templateRepository;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final TemplateDataTypeRepository templateDataTypeRepository;
    private final ValidationRuleRepository validationRuleRepository;
    private final com.modocument.application.port.CurrentUserProvider currentUser;
    private final AuditRecorder audit;

    public CreateTemplateWithFieldsUseCase(TemplateRepository templateRepository,
                                           TemplateFieldsRepository templateFieldsRepository,
                                           TemplateDataTypeRepository templateDataTypeRepository,
                                           ValidationRuleRepository validationRuleRepository,
                                           com.modocument.application.port.CurrentUserProvider currentUser) {
        this(templateRepository, templateFieldsRepository, templateDataTypeRepository,
                validationRuleRepository, currentUser, spec -> { });
    }

    @Autowired
    public CreateTemplateWithFieldsUseCase(TemplateRepository templateRepository,
                                           TemplateFieldsRepository templateFieldsRepository,
                                           TemplateDataTypeRepository templateDataTypeRepository,
                                           ValidationRuleRepository validationRuleRepository,
                                           com.modocument.application.port.CurrentUserProvider currentUser,
                                           AuditRecorder audit) {
        this.audit = audit;
        this.templateRepository = templateRepository;
        this.templateFieldsRepository = templateFieldsRepository;
        this.templateDataTypeRepository = templateDataTypeRepository;
        this.validationRuleRepository = validationRuleRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public CreateTemplateResponse handle(CreateTemplateRequest request) {
        List<TemplateDataType> dataTypes = templateDataTypeRepository.list();
        List<TemplateValidationError> errors = validate(request, dataTypes);
        TemplateVisibility visibility = TemplateVisibility.PRIVATE;
        try {
            TemplateVisibility requested = TemplateVisibilityRequest.parse(request.getVisibility());
            if (requested != null) {
                visibility = requested;
            }
        } catch (IllegalArgumentException unknown) {
            errors.add(new TemplateValidationError(null, null, unknown.getMessage()));
        }
        if (!errors.isEmpty()) {
            throw new TemplateDefinitionException(errors);
        }
        if (visibility == TemplateVisibility.BANK && !callerCanPublish()) {
            throw new AccessDeniedException("Only an administrator (template:publish) can create"
                    + " a template shared with the bank.");
        }

        Template template = new Template(0, request.getName().trim(), request.getDescription());
        template.setVisibility(visibility);
        ExtractionMode mode = ExtractionMode.parse(request.getExtractionMode());
        template.setExtractionMode(mode == null ? null : mode.name());
        template.setOwnerUserId(currentUser.requireUserId());
        int bankId = currentUser.requireBankId();
        template.setBankId(bankId);
        templateRepository.add(template);

        List<CreateTemplateResponse.CreatedField> createdFields = new ArrayList<>();
        for (CreateTemplateFieldDto fieldDto : request.getFields()) {
            TemplateDataType dataType = resolveDataType(fieldDto.getDataType(), dataTypes).orElseThrow();

            TemplateFields field = new TemplateFields();
            field.setTemplateId(template.getTemplateId());
            field.setBankId(bankId);
            field.setFieldName(fieldDto.getFieldName().trim());
            field.setTemplateDataTypeId(dataType.getTemplateDataTypeId());
            // Explicit PII tag (ws12, PRIV-004): validated above; null means
            // "auto-classify on first use" (PiiClassifier persists its result).
            field.setPiiKind(parsePiiKind(fieldDto.getPiiKind()).orElse(null));
            // ws23 extraction hints, vetted in validate() above.
            field.setAliases(fieldDto.getAliases());
            field.setLabelPattern(fieldDto.getLabelPattern());
            field.setValuePattern(fieldDto.getValuePattern());
            field.setDateFormats(fieldDto.getDateFormats());
            templateFieldsRepository.add(field);

            List<CreateTemplateResponse.CreatedRule> createdRules = new ArrayList<>();
            for (CreateTemplateRuleDto ruleDto : rulesOf(fieldDto)) {
                RuleType ruleType = parseRuleType(ruleDto.getRuleType()).orElseThrow();
                String configJson = toConfigJson(ruleDto.getConfig());
                ValidationRule rule = new ValidationRule(
                        template.getTemplateId(), field.getFieldName(), ruleType, configJson);
                rule.setBankId(bankId);
                validationRuleRepository.save(rule);
                createdRules.add(new CreateTemplateResponse.CreatedRule(ruleType.name(), configJson));
            }

            createdFields.add(new CreateTemplateResponse.CreatedField(
                    field.getTemplateFieldsId(), field.getFieldName(),
                    dataType.getTemplateDataTypeId(), dataType.getDataType(),
                    field.getPiiKind() == null ? null : field.getPiiKind().name(),
                    createdRules,
                    field.getAliases(), field.getLabelPattern(), field.getValuePattern(),
                    field.getDateFormats()));
        }

        // ws28: one TEMPLATE_CREATED for the whole composite (same transaction:
        // a rolled-back create leaves no audit row), with the definition's size.
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("name", template.getName());
        created.put("visibility", visibility.name());
        created.put("extractionMode", template.getExtractionMode());
        created.put("fieldCount", createdFields.size());
        created.put("ruleCount", createdFields.stream().mapToInt(f -> f.rules().size()).sum());
        created.put("via", "CreateWithFields");
        audit.success(AuditActions.TEMPLATE_CREATED, "Template",
                String.valueOf(template.getTemplateId()), null, created);

        return new CreateTemplateResponse(
                template.getTemplateId(), template.getName(), template.getDescription(), createdFields,
                visibility.name(), template.getExtractionMode());
    }

    private boolean callerCanPublish() {
        Role role;
        try {
            role = currentUser.role();
        } catch (IllegalStateException noRole) {
            return false;
        }
        return RolePermissions.isGranted(role, Permission.TEMPLATE_PUBLISH);
    }

    private List<TemplateValidationError> validate(CreateTemplateRequest request,
                                                   List<TemplateDataType> dataTypes) {
        List<TemplateValidationError> errors = new ArrayList<>();

        if (request.getName() == null || request.getName().isBlank()) {
            errors.add(new TemplateValidationError(null, null, "Template name is required."));
        }
        try {
            ExtractionMode.parse(request.getExtractionMode());
        } catch (IllegalArgumentException e) {
            errors.add(new TemplateValidationError(null, null, e.getMessage()));
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
            if (field.getPiiKind() != null && !field.getPiiKind().isBlank()
                    && parsePiiKind(field.getPiiKind()).isEmpty()) {
                errors.add(new TemplateValidationError(fieldName, null,
                        "Unknown PII kind '" + field.getPiiKind()
                                + "'; use NIC, ACCOUNT_NUMBER, EMAIL, PHONE, NAME, or NONE."));
            }
            for (String problem : TemplateFieldHints.problems(field.getAliases(),
                    field.getLabelPattern(), field.getValuePattern(), field.getDateFormats())) {
                errors.add(new TemplateValidationError(fieldName, null, problem));
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

    /** PII kinds travel by enum name, case-insensitively; null/blank = untagged. */
    public static Optional<PiiKind> parsePiiKind(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PiiKind.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
