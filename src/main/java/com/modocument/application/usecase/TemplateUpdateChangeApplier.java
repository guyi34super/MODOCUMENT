package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.ExtractionMode;
import org.springframework.stereotype.Component;

/**
 * TEMPLATE_UPDATE under dual control (ws10). SUBMIT verifies the maker may
 * change the template (ws22 {@link TemplateOwnership#requireWritable}: a
 * template they cannot read is a 404, a bank-shared one without
 * template:publish a 403 — exactly like the direct endpoint); APPLY
 * delegates to {@link UpdateTemplateUseCase} judged for the MAKER — the
 * checker approves the maker's change, they do not lend the maker their
 * rights.
 *
 * <p>ws23: the payload may carry {@code extractionMode} (a mode name, or
 * null to inherit the bank default); without the key the mode is kept.
 */
@Component
public class TemplateUpdateChangeApplier implements PendingChangeApplier {

    private final TemplateOwnership ownership;
    private final UpdateTemplateUseCase updateTemplate;
    private final ObjectMapper objectMapper;

    public TemplateUpdateChangeApplier(TemplateOwnership ownership,
                                       UpdateTemplateUseCase updateTemplate,
                                       ObjectMapper objectMapper) {
        this.ownership = ownership;
        this.updateTemplate = updateTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.TEMPLATE_UPDATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int templateId = ChangePayloads.requireInt(payload, "templateId");
        String name = ChangePayloads.requireText(payload, "name", 255);
        String description = ChangePayloads.optionalText(payload, "description", 255);
        UpdateTemplateUseCase.ExtractionModeChange modeChange = modeChange(payload);
        ownership.requireWritable(templateId);

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("templateId", templateId);
        stored.put("name", name);
        if (description != null) {
            stored.put("description", description);
        }
        if (modeChange != null) {
            if (modeChange.mode() == null) {
                stored.putNull("extractionMode");
            } else {
                stored.put("extractionMode", modeChange.mode().name());
            }
        }
        return new Prepared(stored, String.valueOf(templateId));
    }

    /** Null when the payload does not name the mode; a 400 for an unknown one. */
    private static UpdateTemplateUseCase.ExtractionModeChange modeChange(JsonNode payload) {
        if (payload == null || !payload.has("extractionMode")) {
            return null;
        }
        JsonNode value = payload.get("extractionMode");
        return new UpdateTemplateUseCase.ExtractionModeChange(
                value == null || value.isNull() ? null : ExtractionMode.parse(value.asText()));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        updateTemplate.handle(
                ChangePayloads.requireInt(payload, "templateId"),
                ChangePayloads.requireText(payload, "name", 255),
                ChangePayloads.optionalText(payload, "description", 255),
                modeChange(payload),
                change.getMakerUserId());
    }
}
