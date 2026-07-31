package com.modocument.api.controller;

import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportDetailsControllerTest {

    private static final int USER = 1;
    private final ImportDetailsRepository repo = mock(ImportDetailsRepository.class);
    private final ImportHistoryRepository imports = mock(ImportHistoryRepository.class);
    private final ImportDetailsController controller =
            new ImportDetailsController(repo, imports, () -> USER);

    {
        // Parent import #1 belongs to the caller in every scenario below.
        ImportHistory parent = new ImportHistory();
        parent.setImportHistoryId(1);
        parent.setOwnerUserId(USER);
        when(imports.getByIdAndOwner(1, USER)).thenReturn(Optional.of(parent));
        when(imports.listByOwner(USER)).thenReturn(List.of(parent));
    }

    private static ImportDetails importDetails(int id, String rawJson) {
        ImportDetails details = new ImportDetails();
        details.setImportDetailsId(id);
        details.setImportHistoryId(1);
        details.setProcessStepId(1);
        details.setRawJson(rawJson);
        return details;
    }

    @Test
    void getAllReturnsOkWithListOfImportDetails() {
        when(repo.list()).thenReturn(List.of(
                importDetails(1, "{}"),
                importDetails(2, "{}")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getImportDetailsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithImportDetails() {
        when(repo.getById(1)).thenReturn(Optional.of(importDetails(1, "{\"a\":1}")));

        ResponseEntity<?> result = controller.getImportDetailsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImportDetails returned = (ImportDetails) result.getBody();
        assertThat(returned.getImportDetailsId()).isEqualTo(1);
        assertThat(returned.getImportHistoryId()).isEqualTo(1);
        assertThat(returned.getProcessStepId()).isEqualTo(1);
        assertThat(returned.getRawJson()).isEqualTo("{\"a\":1}");
    }

    @Test
    void createReturnsCreatedWithNewImportDetails() {
        when(repo.add(any(ImportDetails.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.create(importDetails(0, "{\"new\":true}"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ImportDetails returned = (ImportDetails) result.getBody();
        assertThat(returned.getRawJson()).isEqualTo("{\"new\":true}");
    }

    @Test
    void updateReturnsNotFoundWhenImportDetailsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(1, importDetails(1, "{}"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        ImportDetails existing = importDetails(1, "{\"old\":true}");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(ImportDetails.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(1, importDetails(1, "{\"new\":true}"));

        assertThat(existing.getRawJson()).isEqualTo("{\"new\":true}");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getById(1)).thenReturn(Optional.of(importDetails(1, "{}")));

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenImportDetailsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
