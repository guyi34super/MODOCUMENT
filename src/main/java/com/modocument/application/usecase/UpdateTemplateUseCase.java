package com.modocument.application.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ExtractionMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Renames/redescribes a template, owner-scoped (extracted from
 * TemplateController for ws10: the maker/checker TEMPLATE_UPDATE applier
 * and the config-off direct endpoint share this ONE implementation, so the
 * ownership rule and the C7 old/new audit can never diverge).
 */
@Service
public class UpdateTemplateUseCase {

    private final TemplateRepository templates;
    private final AuditRecorder audit;

    public UpdateTemplateUseCase(TemplateRepository templates, AuditRecorder audit) {
        this.templates = templates;
        this.audit = audit;
    }

    /**
     * ws23: a change of the template's extraction mode; {@code mode} null
     * resets it to "inherit the bank default". Passing no change (null)
     * keeps the stored mode.
     */
    public record ExtractionModeChange(ExtractionMode mode) {
    }

    /** Renames/redescribes; the extraction mode is kept. */
    @Transactional
    public Template handle(int templateId, String name, String description, int ownerUserId) {
        return handle(templateId, name, description, null, ownerUserId);
    }

    /**
     * @param ownerUserId whose template this must be: the caller on the
     *     direct path, the MAKER when an approved pending change applies —
     *     a template the owner does not own is a 404, never an edit.
     */
    @Transactional
    public Template handle(int templateId, String name, String description,
                           ExtractionModeChange modeChange, int ownerUserId) {
        Template existing = templates.getByIdAndOwner(templateId, ownerUserId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template " + templateId + " not found."));
        // Exemplar of the C7 old/new pattern other domains should copy: a
        // small field-level snapshot before and after, via AuditRecorder's
        // one-liner (actor/bank/request enrichment happens in the recorder).
        Map<String, Object> before = snapshot(existing);
        existing.setName(name);
        existing.setDescription(description);
        if (modeChange != null) {
            existing.setExtractionMode(modeChange.mode() == null ? null : modeChange.mode().name());
        }

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
        values.put("extractionMode", template.getExtractionMode());
        return values;
    }
}
