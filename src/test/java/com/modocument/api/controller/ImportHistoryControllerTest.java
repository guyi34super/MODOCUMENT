package com.modocument.api.controller;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.domain.entity.ImportHistory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportHistoryControllerTest {

    private static final int USER = 1;
    private final ImportHistoryRepository repo = mock(ImportHistoryRepository.class);
    private final ImportHistoryController controller = new ImportHistoryController(repo, FixedUser.provider(USER));

    private static ImportHistory importHistory(int id, String fileName) {
        ImportHistory history = new ImportHistory();
        history.setImportHistoryId(id);
        history.setImportDate(LocalDateTime.of(2025, 1, 1, 0, 0));
        history.setFileName(fileName);
        history.setCompanyId(1000);
        history.setTemplateId(1);
        return history;
    }

    @Test
    void getAllReturnsOkWithListOfImportHistory() {
        when(repo.listByOwner(USER)).thenReturn(List.of(
                importHistory(1, "file1.csv"),
                importHistory(2, "file2.csv")));

        ResponseEntity<?> result = controller.getAll();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) result.getBody()).hasSize(2);
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getImportHistoryById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithImportHistory() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.of(importHistory(1, "file1.csv")));

        ResponseEntity<?> result = controller.getImportHistoryById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImportHistory returned = (ImportHistory) result.getBody();
        assertThat(returned.getImportHistoryId()).isEqualTo(1);
        assertThat(returned.getFileName()).isEqualTo("file1.csv");
        assertThat(returned.getCompanyId()).isEqualTo(1000);
        assertThat(returned.getTemplateId()).isEqualTo(1);
    }

    @Test
    void createReturnsCreatedWithNewImportHistory() {
        when(repo.add(any(ImportHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.create(importHistory(0, "new.csv"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ImportHistory returned = (ImportHistory) result.getBody();
        assertThat(returned.getFileName()).isEqualTo("new.csv");
    }

    @Test
    void updateReturnsNotFoundWhenImportHistoryDoesNotExist() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(1, importHistory(1, "test.csv"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        ImportHistory existing = importHistory(1, "old.csv");
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.of(existing));
        when(repo.update(any(ImportHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(1, importHistory(1, "new.csv"));

        assertThat(existing.getFileName()).isEqualTo("new.csv");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.of(importHistory(1, "file1.csv")));

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenImportHistoryDoesNotExist() {
        when(repo.getByIdAndOwner(1, USER)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
