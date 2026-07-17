package com.modocument.api.controller;

import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.domain.entity.TemplateFields;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateFieldsControllerTest {

    private final TemplateFieldsRepository repo = mock(TemplateFieldsRepository.class);
    private final TemplateFieldsController controller = new TemplateFieldsController(repo);

    private static TemplateFields templateFields(int id, int templateId, String fieldName) {
        TemplateFields fields = new TemplateFields();
        fields.setTemplateFieldsId(id);
        fields.setTemplateId(templateId);
        fields.setFieldName(fieldName);
        return fields;
    }

    @Test
    void getAllReturnsOkWithListOfTemplateFields() {
        when(repo.list()).thenReturn(List.of(
                templateFields(1, 1, "FirstName"),
                templateFields(2, 1, "LastName")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getTemplateFieldsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithTemplateFields() {
        when(repo.getById(1)).thenReturn(Optional.of(templateFields(1, 1, "FirstName")));

        ResponseEntity<?> result = controller.getTemplateFieldsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        TemplateFields returned = (TemplateFields) result.getBody();
        assertThat(returned.getTemplateFieldsId()).isEqualTo(1);
        assertThat(returned.getTemplateId()).isEqualTo(1);
        assertThat(returned.getFieldName()).isEqualTo("FirstName");
    }

    @Test
    void createReturnsCreatedWithNewTemplateFields() {
        ResponseEntity<?> result = controller.create(templateFields(0, 1, "NewField"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TemplateFields returned = (TemplateFields) result.getBody();
        assertThat(returned.getFieldName()).isEqualTo("NewField");
        assertThat(returned.getTemplateId()).isEqualTo(1);
    }

    @Test
    void updateReturnsNotFoundWhenTemplateFieldsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(1, templateFields(1, 1, "TestField"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        TemplateFields existing = templateFields(1, 1, "OldField");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(TemplateFields.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(1, templateFields(1, 2, "NewField"));

        assertThat(existing.getFieldName()).isEqualTo("NewField");
        assertThat(existing.getTemplateId()).isEqualTo(2);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getById(1)).thenReturn(Optional.of(templateFields(1, 1, "FirstName")));

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenTemplateFieldsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
