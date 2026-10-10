package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ws23: a maker/checker TEMPLATE_UPDATE carries the extraction mode (absent = keep). */
class TemplateUpdateChangeApplierModeTest {

    private static final int MAKER = 7;
    private final ObjectMapper json = new ObjectMapper();
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateUpdateChangeApplier applier = new TemplateUpdateChangeApplier(
            templates, FixedUser.provider(MAKER),
            new UpdateTemplateUseCase(templates, mock(AuditRecorder.class)), json);
    private Template template;

    @BeforeEach
    void storedTemplate() {
        template = new Template(3, "T", null);
        template.setExtractionMode("CODE_ONLY");
        when(templates.getByIdAndOwner(3, MAKER)).thenReturn(Optional.of(template));
        when(templates.update(any(Template.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PendingChange approved(ObjectNode payload) {
        PendingChangeApplier.Prepared prepared = applier.prepare(payload);
        PendingChange change = new PendingChange();
        change.setPayloadJson(prepared.payload().toString());
        change.setMakerUserId(MAKER);
        return change;
    }

    private ObjectNode payload() {
        ObjectNode payload = json.createObjectNode();
        payload.put("templateId", 3);
        payload.put("name", "Renamed");
        return payload;
    }

    @Test
    void aRenameWithoutTheModeKeepsIt() {
        applier.apply(approved(payload()));

        assertThat(template.getName()).isEqualTo("Renamed");
        assertThat(template.getExtractionMode()).isEqualTo("CODE_ONLY");
    }

    @Test
    void theModeTravelsThroughApproval() {
        ObjectNode payload = payload();
        payload.put("extractionMode", "ai_only");

        PendingChange change = approved(payload);
        assertThat(change.getPayloadJson()).contains("\"extractionMode\":\"AI_ONLY\"");
        applier.apply(change);

        assertThat(template.getExtractionMode()).isEqualTo("AI_ONLY");
    }

    @Test
    void anExplicitNullResetsToTheBankDefault() {
        ObjectNode payload = payload();
        payload.putNull("extractionMode");

        applier.apply(approved(payload));

        assertThat(template.getExtractionMode()).isNull();
    }

    @Test
    void anUnknownModeIsRefusedAtSubmit() {
        ObjectNode payload = payload();
        payload.put("extractionMode", "MAGIC");

        assertThatThrownBy(() -> applier.prepare(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown extraction mode");
    }
}
