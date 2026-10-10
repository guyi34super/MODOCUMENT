package com.modocument.application.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.Template;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renames/redescribes a template (extracted from TemplateController for
 * ws10: the maker/checker TEMPLATE_UPDATE applier and the config-off direct
 * endpoint share this ONE implementation, so the write rule and the C7
 * old/new audit can never diverge). ws22: the write rule is
 * {@link TemplateOwnership#requireWritableBy} — the owner of a PRIVATE
 * template, a template:publish holder for a BANK-shared one.
 */
@Service
public class UpdateTemplateUseCase {

    private final TemplateOwnership ownership;
    private final TemplateRepository templates;
    private final AuditRecorder audit;

    public UpdateTemplateUseCase(TemplateOwnership ownership, TemplateRepository templates,
                                 AuditRecorder audit) {
        this.ownership = ownership;
        this.templates = templates;
        this.audit = audit;
    }

    /**
     * @param actingUserId who changes it: the caller on the direct path, the
     *     MAKER when an approved pending change applies — a template that
     *     user cannot read is a 404, a shared one without template:publish a
     *     403, never an edit.
     */
    @Transactional
    public Template handle(int templateId, String name, String description, int actingUserId) {
        Template existing = ownership.requireWritableBy(templateId, actingUserId);
        // Exemplar of the C7 old/new pattern other domains should copy: a
        // small field-level snapshot before and after, via AuditRecorder's
        // one-liner (actor/bank/request enrichment happens in the recorder).
        Map<String, Object> before = snapshot(existing);
        existing.setName(name);
        existing.setDescription(description);

        Template response = templates.update(existing);
        audit.success("TEMPLATE_UPDATED", "Template", String.valueOf(response.getTemplateId()),
                before, snapshot(response));
        return response;
    }

    /** The audited field-level view of a template (C7 old/new values). */
    private static Map<String, Object> snapshot(Template template) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", template.getName());
        values.put("description", template.getDescription());
        return values;
    }
}
