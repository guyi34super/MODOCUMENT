package com.modocument.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.testsupport.FixedUser;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.dto.TemplateDto;
import com.modocument.application.dto.TemplateVisibilityRequest;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.DeleteTemplateUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.application.usecase.TemplateVisibilityUseCase;
import com.modocument.application.usecase.UpdateTemplateUseCase;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.TemplateVisibility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TemplateControllerTest {

    private static final int USER = 1;
    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final TemplateVisibilityUseCase views = mock(TemplateVisibilityUseCase.class);
    private final UpdateTemplateUseCase updateTemplate = mock(UpdateTemplateUseCase.class);
    private final DeleteTemplateUseCase deleteTemplate = mock(DeleteTemplateUseCase.class);
    private final PendingChangeUseCase pendingChanges = mock(PendingChangeUseCase.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // CurrentUserProvider is a functional interface — a fixed caller is enough.
    private TemplateController controller(boolean makerCheckerForTemplates) {
        return new TemplateController(repo, views, updateTemplate, deleteTemplate, pendingChanges,
                FixedUser.provider(USER), objectMapper, makerCheckerForTemplates);
    }

    /** The config-off direct path (dev setups); production defaults to gated. */
    private final TemplateController direct = controller(false);
    private final TemplateController gated = controller(true);

    private static TemplateDto view(int id, String name, String description) {
        return new TemplateDto(id, name, description, "PRIVATE", true, true, null);
    }

    @Test
    void getAllReturnsOkWithListOfTemplates() {
        when(views.list()).thenReturn(List.of(
                view(1, "TemplateName1", "TemplateDescription1"),
                view(2, "TemplateName2", "TemplateDescription2")));

        ResponseEntity<?> result = direct.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(views.get(1)).thenThrow(new NoSuchElementException("Template 1 not found."));

        ResponseEntity<?> result = direct.getTemplateById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithTemplate() {
        when(views.get(1)).thenReturn(view(1, "TemplateName1", "TemplateDescription1"));

        ResponseEntity<?> result = direct.getTemplateById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        TemplateDto returned = (TemplateDto) result.getBody();
        assertThat(returned.templateId()).isEqualTo(1);
        assertThat(returned.name()).isEqualTo("TemplateName1");
        assertThat(returned.description()).isEqualTo("TemplateDescription1");
        assertThat(returned.visibility()).isEqualTo("PRIVATE");
    }

    @Test
    void visibilityDelegatesTheParsedValue() {
        TemplateDto shared = new TemplateDto(4, "T", null, "BANK", true, true, null);
        when(views.changeVisibility(4, TemplateVisibility.BANK)).thenReturn(shared);

        ResponseEntity<TemplateDto> result =
                direct.setVisibility(4, new TemplateVisibilityRequest("bank"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(shared);
    }

    @Test
    void createReturnsCreatedWithNewTemplate() {
        ResponseEntity<?> result = direct.create(
                new Template(0, "NewTemplateName", "NewTemplateDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Template returned = (Template) result.getBody();
        assertThat(returned.getName()).isEqualTo("NewTemplateName");
        assertThat(returned.getDescription()).isEqualTo("NewTemplateDescription");
    }

    // ------------------------------------------- config-off: direct apply

    @Test
    void updateReturnsNotFoundWhenTemplateDoesNotExist() {
        when(updateTemplate.handle(eq(1), anyString(), anyString(), eq(USER)))
                .thenThrow(new NoSuchElementException("Template 1 not found."));

        ResponseEntity<?> result = direct.update(new Template(1, "TestName", "TestDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateDelegatesToTheUseCaseWhenUngated() {
        when(updateTemplate.handle(1, "NewName", "NewDescription", USER))
                .thenReturn(new Template(1, "NewName", "NewDescription"));

        ResponseEntity<?> result = direct.update(new Template(1, "NewName", "NewDescription"));

        // The original endpoint answers 201 CreatedAtAction on update; kept.
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Template) result.getBody()).getName()).isEqualTo("NewName");
        verify(updateTemplate).handle(1, "NewName", "NewDescription", USER);
        verifyNoInteractions(pendingChanges);
    }

    @Test
    void deleteReturnsNoContentWhenUngated() {
        ResponseEntity<?> result = direct.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteTemplate).handle(1, USER);
        verifyNoInteractions(pendingChanges);
    }

    @Test
    void deleteReturnsNotFoundWhenTemplateDoesNotExist() {
        doThrow(new NoSuchElementException("Template 1 not found."))
                .when(deleteTemplate).handle(1, USER);

        ResponseEntity<?> result = direct.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().toString()).contains("not found");
    }

    @Test
    void deleteReturnsConflictWhenTemplateIsStillInUse() {
        doThrow(new IllegalStateException("This template is still in use by 2 upload jobs."))
                .when(deleteTemplate).handle(1, USER);

        ResponseEntity<?> result = direct.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().toString()).contains("still in use");
    }

    // ------------------------------------- gated: maker/checker submission

    private static PendingChangeDto pendingDto(String changeType, String targetId) {
        return new PendingChangeDto(42, changeType, "Template", targetId, "REQUESTED",
                USER, "maker@example.com", null, null, null, null, null, null, null, null);
    }

    @Test
    void updateSubmitsAPendingChangeWhenGated() {
        PendingChangeDto pending = pendingDto("TEMPLATE_UPDATE", "7");
        when(pendingChanges.submit(eq(ChangeType.TEMPLATE_UPDATE), any(JsonNode.class), isNull()))
                .thenReturn(pending);

        ResponseEntity<?> result = gated.update(new Template(7, "NewName", "NewDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isSameAs(pending);
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(pendingChanges).submit(eq(ChangeType.TEMPLATE_UPDATE), payload.capture(), isNull());
        assertThat(payload.getValue().get("templateId").asInt()).isEqualTo(7);
        assertThat(payload.getValue().get("name").asText()).isEqualTo("NewName");
        // Nothing applied directly.
        verifyNoInteractions(updateTemplate);
    }

    @Test
    void deleteSubmitsAPendingChangeWhenGated() {
        PendingChangeDto pending = pendingDto("TEMPLATE_DELETE", "7");
        when(pendingChanges.submit(eq(ChangeType.TEMPLATE_DELETE), any(JsonNode.class), isNull()))
                .thenReturn(pending);

        ResponseEntity<?> result = gated.delete(7);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isSameAs(pending);
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(pendingChanges).submit(eq(ChangeType.TEMPLATE_DELETE), payload.capture(), isNull());
        assertThat(payload.getValue().get("templateId").asInt()).isEqualTo(7);
        verifyNoInteractions(deleteTemplate);
    }
}
