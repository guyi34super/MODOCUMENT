package com.modocument.api.controller;

import com.modocument.application.dto.ExtractionJobDto;
import com.modocument.application.usecase.BridgeToTemplateUseCase;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.InferSchemaUseCase;
import com.modocument.application.usecase.SummarizeExtractionUseCase;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.infrastructure.job.JobWaitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtractionJobControllerTest {

    private final ExtractionJobUseCase jobs = mock(ExtractionJobUseCase.class);
    private final JobWaitService jobWaitService = mock(JobWaitService.class);
    private final ExtractionJobController controller = new ExtractionJobController(
            jobs,
            mock(BridgeToTemplateUseCase.class),
            mock(InferSchemaUseCase.class),
            mock(SummarizeExtractionUseCase.class),
            mock(RawRecordStore.class),
            mock(ProcessedRecordStore.class),
            mock(ExtractionAuditLog.class),
            jobWaitService);

    @Test
    void getWithWaitDelegatesToJobWaitService() {
        ExtractionJob job = new ExtractionJob();
        job.setId("job-1");
        job.setStatus(ExtractionJobStatus.COMPLETED);
        ExtractionJobDto dto = ExtractionJobDto.from(job, Map.of(ExtractionTaskStatus.COMPLETED, 2L));
        when(jobWaitService.waitForExtractionView("job-1")).thenReturn(dto);

        ResponseEntity<?> result = controller.get("job-1", true);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(dto);
    }

    @Test
    void startWithWaitBlocksAndReturnsCompletedJob() {
        ExtractionJob job = new ExtractionJob();
        job.setId("job-1");
        job.setStatus(ExtractionJobStatus.COMPLETED);
        ExtractionJobDto dto = ExtractionJobDto.from(job, Map.of(ExtractionTaskStatus.COMPLETED, 2L));
        when(jobs.get("job-1")).thenReturn(job);
        when(jobWaitService.waitForExtractionView("job-1")).thenReturn(dto);

        ResponseEntity<?> result = controller.start("job-1", true);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(dto);
    }
}
