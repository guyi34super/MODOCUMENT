package com.modocument.api.controller;

import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.export.DatabaseExportService;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.file.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionExportControllerTest {

    private static final String TABLE_JSON =
            "{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"Ada\"}]}";

    private final IngestionJobUseCase jobs = mock(IngestionJobUseCase.class);
    private final FileExportService fileExport = new FileExportService();
    private final DatabaseExportService databaseExport = mock(DatabaseExportService.class);

    @TempDir
    Path tempDir;

    private IngestionExportController controller() {
        return new IngestionExportController(jobs, fileExport, databaseExport,
                new StorageProperties(tempDir.toString()));
    }

    private static IngestionJob job(JobStep step, JobStatus status, String mappedRowsJson) {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setCurrentStep(step);
        job.setStatus(status);
        job.setMappedRowsJson(mappedRowsJson);
        return job;
    }

    @Test
    void exportsValidatedJobAsCsv() {
        IngestionJob validated = job(JobStep.VALIDATED, JobStatus.COMPLETED, TABLE_JSON);
        when(jobs.get(7)).thenReturn(validated);
        when(jobs.recordExport(org.mockito.ArgumentMatchers.eq(validated),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(validated);

        ResponseEntity<?> result = controller().exportFile(7, "csv");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String((byte[]) result.getBody())).contains("FirstName").contains("Ada");
    }

    @Test
    void refusesToExportACancelledJob() {
        when(jobs.get(7)).thenReturn(job(JobStep.VALIDATED, JobStatus.CANCELLED, TABLE_JSON));

        assertThatThrownBy(() -> controller().exportFile(7, "csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void refusesToExportBeforeValidation() {
        when(jobs.get(7)).thenReturn(job(JobStep.MAPPED, JobStatus.COMPLETED, TABLE_JSON));

        assertThatThrownBy(() -> controller().exportFile(7, "csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not been validated");
    }

    @Test
    void refusesToExportAnEmptyTable() {
        when(jobs.get(7)).thenReturn(job(JobStep.VALIDATED, JobStatus.COMPLETED,
                "{\"columns\":[],\"rows\":[]}"));

        assertThatThrownBy(() -> controller().exportFile(7, "csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no mapped columns");
    }

    @Test
    void conflictHandlerReportsTheReason() {
        ResponseEntity<?> result =
                controller().conflict(new IllegalStateException("Job 7 is cancelled."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().toString()).contains("cancelled");
    }
}
