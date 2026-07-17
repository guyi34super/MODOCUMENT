package com.modocument.api.controller;

import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.domain.entity.TemplateDataType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateDataTypesControllerTest {

    private final TemplateDataTypeRepository repo = mock(TemplateDataTypeRepository.class);
    private final TemplateDataTypesController controller = new TemplateDataTypesController(repo);

    @Test
    void getAllReturnsOkWithListOfDataTypes() {
        when(repo.list()).thenReturn(List.of(
                new TemplateDataType(1, "Text"),
                new TemplateDataType(2, "Number")));

        ResponseEntity<?> result = controller.getAllTemplateDataTypes();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getTemplateDataTypeById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithDataType() {
        when(repo.getById(1)).thenReturn(Optional.of(new TemplateDataType(1, "Text")));

        ResponseEntity<?> result = controller.getTemplateDataTypeById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        TemplateDataType returned = (TemplateDataType) result.getBody();
        assertThat(returned.getTemplateDataTypeId()).isEqualTo(1);
        assertThat(returned.getDataType()).isEqualTo("Text");
    }

    @Test
    void createReturnsCreatedWithNewDataType() {
        ResponseEntity<?> result = controller.createTemplateDataType(new TemplateDataType(0, "Date"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TemplateDataType returned = (TemplateDataType) result.getBody();
        assertThat(returned.getDataType()).isEqualTo("Date");
    }

    @Test
    void updateReturnsNotFoundWhenDataTypeDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.updateTemplateDataType(new TemplateDataType(1, "Text"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        TemplateDataType existing = new TemplateDataType(1, "Text");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(TemplateDataType.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.updateTemplateDataType(new TemplateDataType(1, "Number"));

        assertThat(existing.getDataType()).isEqualTo("Number");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getById(1)).thenReturn(Optional.of(new TemplateDataType(1, "Text")));

        ResponseEntity<?> result = controller.deleteTemplateDataType(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenDataTypeDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.deleteTemplateDataType(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
