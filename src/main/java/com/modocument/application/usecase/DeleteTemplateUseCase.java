package com.modocument.application.usecase;

import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public DeleteTemplateUseCase(TemplateRepository templates,
                                 TemplateFieldsRepository fields,
                                 ValidationRuleRepository rules,
                                 IngestionJobRepository jobs,
                                 ImportHistoryRepository imports) {
        this.templates = templates;
        this.fields = fields;
        this.rules = rules;
        this.jobs = jobs;
        this.imports = imports;
    }

    @Transactional
    public void handle(int templateId) {
        if (templates.getById(templateId).isEmpty()) {
            throw new NoSuchElementException("Template " + templateId + " not found.");
        }

        int jobCount = jobs.countByTemplateId(templateId);
        int importCount = imports.countByTemplateId(templateId);
        if (jobCount > 0 || importCount > 0) {
            throw new IllegalStateException(describeBlockers(jobCount, importCount));
        }

        for (ValidationRule rule : List.copyOf(rules.getRulesByTemplate(templateId))) {
            rules.delete(rule.getTemplateId(), rule.getFieldName(), rule.getRuleType());
        }
        for (TemplateFields field : List.copyOf(fields.listByTemplateId(templateId))) {
            fields.delete(field.getTemplateFieldsId());
        }
        templates.delete(templateId);
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
