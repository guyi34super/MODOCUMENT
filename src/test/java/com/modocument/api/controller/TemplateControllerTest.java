package com.modocument.api.controller;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.DeleteTemplateUseCase;
import com.modocument.domain.entity.Template;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateControllerTest {

    private static final int USER = 1;
    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final DeleteTemplateUseCase deleteTemplate = mock(DeleteTemplateUseCase.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    // CurrentUserProvider is a functional interface — a fixed caller is enough.
    private final TemplateController controller =
            new TemplateController(repo, deleteTemplate, FixedUser.provider(USER), audit);

    @Test
    void getAllReturnsOkWithListOfTemplates() {
        when(repo.listByOwner(USER)).thenReturn(List.of(
                new Template(1, "TemplateName1", "TemplateDescription1"),
                new Template(2, "TemplateName2", "TemplateDescription2")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getTemplateById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithTemplate() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.of(
                new Template(1, "TemplateName1", "TemplateDescription1")));

        ResponseEntity<?> result = controller.getTemplateById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        Template returned = (Template) result.getBody();
        assertThat(returned.getTemplateId()).isEqualTo(1);
        assertThat(returned.getName()).isEqualTo("TemplateName1");
        assertThat(returned.getDescription()).isEqualTo("TemplateDescription1");
    }

    @Test
    void createReturnsCreatedWithNewTemplate() {
        ResponseEntity<?> result = controller.create(
                new Template(0, "NewTemplateName", "NewTemplateDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Template returned = (Template) result.getBody();
        assertThat(returned.getName()).isEqualTo("NewTemplateName");
        assertThat(returned.getDescription()).isEqualTo("NewTemplateDescription");
    }

    @Test
    void updateReturnsNotFoundWhenTemplateDoesNotExist() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(new Template(1, "TestName", "TestDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        Template existing = new Template(1, "OldName", "OldDescription");
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.of(existing));
        when(repo.update(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(new Template(1, "NewName", "NewDescription"));

        assertThat(existing.getName()).isEqualTo("NewName");
        assertThat(existing.getDescription()).isEqualTo("NewDescription");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The C7 exemplar: old and new field-level snapshots are audited.
        verify(audit).success(
                org.mockito.ArgumentMatchers.eq("TEMPLATE_UPDATED"),
                org.mockito.ArgumentMatchers.eq("Template"),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.argThat(old -> old.toString().contains("OldName")),
                org.mockito.ArgumentMatchers.argThat(now -> now.toString().contains("NewName")));
    }

    @Test
    void deleteReturnsNoContent() {
        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteTemplate).handle(1, USER);
    }

    @Test
    void deleteReturnsNotFoundWhenTemplateDoesNotExist() {
        doThrow(new NoSuchElementException("Template 1 not found."))
                .when(deleteTemplate).handle(1, USER);

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().toString()).contains("not found");
    }

    @Test
    void deleteReturnsConflictWhenTemplateIsStillInUse() {
        doThrow(new IllegalStateException("This template is still in use by 2 upload jobs."))
                .when(deleteTemplate).handle(1, USER);

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().toString()).contains("still in use");
    }
}
