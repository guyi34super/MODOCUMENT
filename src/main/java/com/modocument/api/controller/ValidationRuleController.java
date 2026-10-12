package com.modocument.api.controller;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.LinkedHashMap;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.UpsertValidationRuleRequest;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.usecase.CreateTemplateWithFieldsUseCase;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.RuleType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/** CRUD for per-field validation rules, so the UI can manage custom regex rules. */
@RestController
@Tag(name = "Validation Rules")
public class ValidationRuleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ValidationRuleRepository repo;
    private final TemplateOwnership ownership;
    private final AuditRecorder audit;

    public ValidationRuleController(ValidationRuleRepository repo, TemplateOwnership ownership) {
        this(repo, ownership, spec -> { });
    }

    @Autowired
    public ValidationRuleController(ValidationRuleRepository repo, TemplateOwnership ownership,
                                    AuditRecorder audit) {
        this.repo = repo;
        this.ownership = ownership;
        this.audit = audit;
    }

    /** "templateId:fieldName:ruleType" — the rule's composite key. */
    private static String ruleId(int templateId, String fieldName, RuleType ruleType) {
        return templateId + ":" + fieldName + ":" + ruleType.name();
    }

    private static Map<String, Object> snapshot(int templateId, String fieldName,
                                                RuleType ruleType, String configJson) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("templateId", templateId);
        values.put("fieldName", fieldName);
        values.put("ruleType", ruleType.name());
        if (configJson != null) {
            values.put("config", configJson);
        }
        return values;
    }

    @RequiresPermission(Permission.VALIDATION_READ)
    @GetMapping("/ValidationRules/GetByTemplate/{templateId}")
    public ResponseEntity<?> getByTemplate(@PathVariable int templateId) {
        ownership.requireReadable(templateId);
        return ResponseEntity.ok(repo.getRulesByTemplate(templateId));
    }

    @RequiresPermission(Permission.VALIDATION_WRITE)
    @PutMapping("/ValidationRules/Upsert")
    public ResponseEntity<?> upsert(@Valid @RequestBody UpsertValidationRuleRequest request) {
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fieldName is required."));
        }
        // A rule is part of the template's definition: owner / template:publish.
        ownership.requireWritable(request.getTemplateId());
        Optional<RuleType> ruleType = CreateTemplateWithFieldsUseCase.parseRuleType(request.getRuleType());
        if (ruleType.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown rule type '" + request.getRuleType() + "'."));
        }
        if (ruleType.get() == RuleType.REGEX) {
            Object pattern = request.getConfig() == null ? null : request.getConfig().get("Pattern");
            String problem = RegexPatternChecker.check(pattern == null ? null : pattern.toString());
            if (problem != null) {
                return ResponseEntity.badRequest().body(Map.of("error", problem));
            }
        }

        String configJson;
        try {
            configJson = MAPPER.writeValueAsString(
                    request.getConfig() == null ? Map.of() : request.getConfig());
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "config cannot be serialized."));
        }

        String fieldName = request.getFieldName().trim();
        Optional<ValidationRule> previous = repo.getRulesByTemplate(request.getTemplateId()).stream()
                .filter(r -> fieldName.equals(r.getFieldName()) && r.getRuleType() == ruleType.get())
                .findFirst();
        ValidationRule rule = new ValidationRule(
                request.getTemplateId(), fieldName, ruleType.get(), configJson);
        rule.setBankId(ownership.requireBankId());
        ValidationRule saved = repo.save(rule);
        audit.success(previous.isPresent() ? AuditActions.VALIDATION_RULE_UPDATED
                        : AuditActions.VALIDATION_RULE_CREATED, "ValidationRule",
                ruleId(request.getTemplateId(), fieldName, ruleType.get()),
                previous.map(p -> snapshot(p.getTemplateId(), p.getFieldName(), p.getRuleType(),
                        p.getConfigJson())).orElse(null),
                snapshot(request.getTemplateId(), fieldName, ruleType.get(), configJson));
        return ResponseEntity.ok(saved);
    }

    /**
     * Field name travels as a query parameter: a name like "Province/State"
     * cannot be expressed as a path segment (Spring's firewall rejects the
     * encoded slash), which made the rule undeletable through the UI.
     */
    @RequiresPermission(Permission.VALIDATION_WRITE)
    @DeleteMapping("/ValidationRules")
    public ResponseEntity<?> delete(@RequestParam int templateId,
                                    @RequestParam String fieldName,
                                    @RequestParam String ruleType) {
        return deleteRule(templateId, fieldName, ruleType);
    }

    /** Original path form, kept for callers that already use it. */
    @RequiresPermission(Permission.VALIDATION_WRITE)
    @DeleteMapping("/ValidationRules/{templateId}/{fieldName}/{ruleType}")
    public ResponseEntity<?> deleteByPath(@PathVariable int templateId,
                                          @PathVariable String fieldName,
                                          @PathVariable String ruleType) {
        return deleteRule(templateId, fieldName, ruleType);
    }

    private ResponseEntity<?> deleteRule(int templateId, String fieldName, String ruleType) {
        Optional<RuleType> parsed = CreateTemplateWithFieldsUseCase.parseRuleType(ruleType);
        if (parsed.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown rule type '" + ruleType + "'."));
        }
        ownership.requireWritable(templateId);
        if (repo.delete(templateId, fieldName, parsed.get())) {
            audit.success(AuditActions.VALIDATION_RULE_DELETED, "ValidationRule",
                    ruleId(templateId, fieldName, parsed.get()),
                    snapshot(templateId, fieldName, parsed.get(), null), null);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No " + parsed.get() + " rule on field '"
                                + fieldName + "' for template " + templateId + "."));
    }
}
