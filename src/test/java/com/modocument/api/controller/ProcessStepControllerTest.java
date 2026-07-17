package com.modocument.api.controller;

import com.modocument.application.port.ProcessStepRepository;
import com.modocument.domain.entity.ProcessStep;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessStepControllerTest {

    private final ProcessStepRepository repo = mock(ProcessStepRepository.class);
    private final ProcessStepController controller = new ProcessStepController(repo);

    @Test
    void getAllReturnsOkWithListOfProcessSteps() {
        when(repo.list()).thenReturn(List.of(
                new ProcessStep(1, "Transforming", "Step1"),
                new ProcessStep(2, "Mapping", "Step2")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getProcessStepById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithProcessStep() {
        when(repo.getById(1)).thenReturn(Optional.of(new ProcessStep(1, "Transforming", "Step1")));

        ResponseEntity<?> result = controller.getProcessStepById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProcessStep returned = (ProcessStep) result.getBody();
        assertThat(returned.getProcessStepId()).isEqualTo(1);
        assertThat(returned.getName()).isEqualTo("Transforming");
        assertThat(returned.getDescription()).isEqualTo("Step1");
    }

    @Test
    void createReturnsCreatedWithNewProcessStep() {
        ResponseEntity<?> result = controller.create(new ProcessStep(0, "NewStep", "NewDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProcessStep returned = (ProcessStep) result.getBody();
        assertThat(returned.getName()).isEqualTo("NewStep");
        assertThat(returned.getDescription()).isEqualTo("NewDescription");
    }

    @Test
    void updateReturnsNotFoundWhenProcessStepDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(new ProcessStep(1, "TestName", "TestDescription"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        ProcessStep existing = new ProcessStep(1, "OldName", "OldDescription");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(ProcessStep.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(new ProcessStep(1, "NewName", "NewDescription"));

        assertThat(existing.getName()).isEqualTo("NewName");
        assertThat(existing.getDescription()).isEqualTo("NewDescription");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getById(1)).thenReturn(Optional.of(new ProcessStep(1, null, null)));

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenProcessStepDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
