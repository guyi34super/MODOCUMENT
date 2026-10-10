package com.modocument.application.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Deletes a template together with the definition it owns — its fields and
 * their validation rules — in one transaction. Everything else that points at
 * a template is *data* (upload jobs, import history), and a foreign key would
 * otherwise reject the delete with a bare 500; those cases are refused with an
 * explanation of what still references the template instead.
 */
@Service
public class DeleteTemplateUseCase {

    private final TemplateRepository templates;
    private final TemplateFieldsRepository fields;
    private final ValidationRuleRepository rules;
    private final IngestionJobRepository jobs;
    private final ImportHistoryRepository imports;
    private final AuditRecorder audit;

    public DeleteTemplateUseCase(TemplateRepository templates,
                                 TemplateFieldsRepository fields,
                                 ValidationRuleRepository rules,
                                 IngestionJobRepository jobs,
                                 ImportHistoryRepository imports,
                                 AuditRecorder audit) {
        this.templates = templates;
        this.fields = fields;
        this.rules = rules;
        this.jobs = jobs;
        this.imports = imports;
        this.audit = audit;
    }

    @Transactional
    public void handle(int templateId, int ownerUserId) {
        // Owner-scoped: a template the caller does not own is a 404, so deletion
        // can never reach another user's data.
        Template template = templates.getByIdAndOwner(templateId, ownerUserId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template " + templateId + " not found."));

        int jobCount = jobs.countByTemplateId(templateId);
        int importCount = imports.countByTemplateId(templateId);
        if (jobCount > 0 || importCount > 0) {
            throw new IllegalStateException(describeBlockers(jobCount, importCount));
        }

        // Snapshot what is about to disappear BEFORE deleting it (C7 "old
        // values"): the template's own fields plus a summary of its field
        // names - enough to reconstruct what was lost without dumping rows.
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", template.getName());
        before.put("description", template.getDescription());
        before.put("fields", fields.listByTemplateId(templateId).stream()
                .map(TemplateFields::getFieldName)
                .toList());

        for (ValidationRule rule : List.copyOf(rules.getRulesByTemplate(templateId))) {
            rules.delete(rule.getTemplateId(), rule.getFieldName(), rule.getRuleType());
        }
        for (TemplateFields field : List.copyOf(fields.listByTemplateId(templateId))) {
            fields.delete(field.getTemplateFieldsId());
        }
        templates.delete(templateId);
        // Joins this @Transactional method's transaction: if the delete rolls
        // back, the audit row of a deletion that never happened goes with it.
        audit.success("TEMPLATE_DELETED", "Template", String.valueOf(templateId), before, null);
    }

    private static String describeBlockers(int jobCount, int importCount) {
        StringBuilder message = new StringBuilder("This template is still in use by ");
        if (jobCount > 0) {
            message.append(jobCount).append(jobCount == 1 ? " upload job" : " upload jobs");
        }
        if (jobCount > 0 && importCount > 0) {
            message.append(" and ");
        }
        if (importCount > 0) {
            message.append(importCount).append(importCount == 1 ? " import" : " imports");
        }
        return message.append(". Delete those first, or keep the template for their history.")
                .toString();
    }
}
