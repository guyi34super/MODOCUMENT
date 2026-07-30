package com.modocument.api.controller;

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

    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final DeleteTemplateUseCase deleteTemplate = mock(DeleteTemplateUseCase.class);
    private final TemplateController controller = new TemplateController(repo, deleteTemplate);

    @Test
    void getAllReturnsOkWithListOfTemplates() {
        when(repo.list()).thenReturn(List.of(
                new Template(1, "TemplateName1", "TemplateDescription1"),
                new Template(2, "TemplateName2", "TemplateDescription2")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getTemplateById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithTemplate() {
        when(repo.getById(1)).thenReturn(Optional.of(
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
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(new Template(1, "TestName", "TestDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        Template existing = new Template(1, "OldName", "OldDescription");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(new Template(1, "NewName", "NewDescription"));

        assertThat(existing.getName()).isEqualTo("NewName");
        assertThat(existing.getDescription()).isEqualTo("NewDescription");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteTemplate).handle(1);
    }

    @Test
    void deleteReturnsNotFoundWhenTemplateDoesNotExist() {
        doThrow(new NoSuchElementException("Template 1 not found."))
                .when(deleteTemplate).handle(1);

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().toString()).contains("not found");
    }

    @Test
    void deleteReturnsConflictWhenTemplateIsStillInUse() {
        doThrow(new IllegalStateException("This template is still in use by 2 upload jobs."))
                .when(deleteTemplate).handle(1);

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().toString()).contains("still in use");
    }
}
