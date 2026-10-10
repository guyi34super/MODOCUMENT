package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.ExtractionMode;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * TEMPLATE_UPDATE under dual control (ws10). SUBMIT verifies the maker
 * owns the template (a foreign one is a 404, exactly like the direct
 * endpoint); APPLY delegates to {@link UpdateTemplateUseCase} scoped to the
 * MAKER's ownership — the checker approves the maker's change, they do not
 * acquire edit rights over the template.
 *
 * <p>ws23: the payload may carry {@code extractionMode} (a mode name, or
 * null to inherit the bank default); without the key the mode is kept.
 */
@Component
public class TemplateUpdateChangeApplier implements PendingChangeApplier {

    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final UpdateTemplateUseCase updateTemplate;
    private final ObjectMapper objectMapper;

    public TemplateUpdateChangeApplier(TemplateRepository templates,
                                       CurrentUserProvider currentUser,
                                       UpdateTemplateUseCase updateTemplate,
                                       ObjectMapper objectMapper) {
        this.templates = templates;
        this.currentUser = currentUser;
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
        templates.getByIdAndOwner(templateId, currentUser.requireUserId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Template " + templateId + " not found."));

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
