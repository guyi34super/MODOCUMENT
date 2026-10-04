package com.modocument.api.controller;

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

    public ValidationRuleController(ValidationRuleRepository repo, TemplateOwnership ownership) {
        this.repo = repo;
        this.ownership = ownership;
    }

    @RequiresPermission(Permission.VALIDATION_READ)
    @GetMapping("/ValidationRules/GetByTemplate/{templateId}")
    public ResponseEntity<?> getByTemplate(@PathVariable int templateId) {
        ownership.require(templateId);
        return ResponseEntity.ok(repo.getRulesByTemplate(templateId));
    }

    @RequiresPermission(Permission.VALIDATION_WRITE)
    @PutMapping("/ValidationRules/Upsert")
    public ResponseEntity<?> upsert(@Valid @RequestBody UpsertValidationRuleRequest request) {
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fieldName is required."));
        }
        ownership.require(request.getTemplateId());
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

        ValidationRule rule = new ValidationRule(
                request.getTemplateId(), request.getFieldName().trim(), ruleType.get(), configJson);
        rule.setBankId(ownership.requireBankId());
        ValidationRule saved = repo.save(rule);
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
        ownership.require(templateId);
        return repo.delete(templateId, fieldName, parsed.get())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No " + parsed.get() + " rule on field '"
                                + fieldName + "' for template " + templateId + "."));
    }
}
