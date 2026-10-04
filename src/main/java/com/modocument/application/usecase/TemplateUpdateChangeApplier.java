package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * TEMPLATE_UPDATE under dual control (ws10). SUBMIT verifies the maker
 * owns the template (a foreign one is a 404, exactly like the direct
 * endpoint); APPLY delegates to {@link UpdateTemplateUseCase} scoped to the
 * MAKER's ownership — the checker approves the maker's change, they do not
 * acquire edit rights over the template.
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
        templates.getByIdAndOwner(templateId, currentUser.requireUserId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Template " + templateId + " not found."));

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("templateId", templateId);
        stored.put("name", name);
        if (description != null) {
            stored.put("description", description);
        }
        return new Prepared(stored, String.valueOf(templateId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        updateTemplate.handle(
                ChangePayloads.requireInt(payload, "templateId"),
                ChangePayloads.requireText(payload, "name", 255),
                ChangePayloads.optionalText(payload, "description", 255),
                change.getMakerUserId());
    }
}
