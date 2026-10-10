package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * TEMPLATE_DELETE under dual control (ws10). SUBMIT verifies the maker
 * owns the template; APPLY delegates to {@link DeleteTemplateUseCase}
 * scoped to the MAKER — which re-checks the in-use blockers (jobs,
 * imports) at apply time and audits TEMPLATE_DELETED with the full
 * before-snapshot, exactly like the direct endpoint did.
 */
@Component
public class TemplateDeleteChangeApplier implements PendingChangeApplier {

    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final DeleteTemplateUseCase deleteTemplate;
    private final ObjectMapper objectMapper;

    public TemplateDeleteChangeApplier(TemplateRepository templates,
                                       CurrentUserProvider currentUser,
                                       DeleteTemplateUseCase deleteTemplate,
                                       ObjectMapper objectMapper) {
        this.templates = templates;
        this.currentUser = currentUser;
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
        Template template = templates.getByIdAndOwner(templateId, currentUser.requireUserId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Template " + templateId + " not found."));

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
