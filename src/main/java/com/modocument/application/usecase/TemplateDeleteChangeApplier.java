package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * TEMPLATE_DELETE under dual control (ws10). SUBMIT verifies the maker may
 * change the template (ws22 {@link TemplateOwnership#requireWritable});
 * APPLY delegates to {@link DeleteTemplateUseCase} judged for the MAKER —
 * which re-checks the write rule and the in-use blockers (colleagues'
 * unfinished jobs on a shared template, jobs, imports) at apply time and
 * audits TEMPLATE_DELETED with the full before-snapshot, exactly like the
 * direct endpoint did.
 */
@Component
public class TemplateDeleteChangeApplier implements PendingChangeApplier {

    private final TemplateOwnership ownership;
    private final DeleteTemplateUseCase deleteTemplate;
    private final ObjectMapper objectMapper;

    public TemplateDeleteChangeApplier(TemplateOwnership ownership,
                                       DeleteTemplateUseCase deleteTemplate,
                                       ObjectMapper objectMapper) {
        this.ownership = ownership;
        this.deleteTemplate = deleteTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.TEMPLATE_DELETE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int templateId = ChangePayloads.requireInt(payload, "templateId");
        Template template = ownership.requireWritable(templateId);
        // The maker hears "colleagues still use it" now, not the checker later.
        deleteTemplate.requireNotInUseByColleagues(template);

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("templateId", templateId);
        // For the approvals listing: the checker reviews a named template.
        stored.put("name", template.getName());
        return new Prepared(stored, String.valueOf(templateId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        deleteTemplate.handle(
                ChangePayloads.requireInt(payload, "templateId"),
                change.getMakerUserId());
    }
}
