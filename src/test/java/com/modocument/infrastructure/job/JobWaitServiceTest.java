package com.modocument.infrastructure.job;

import com.modocument.application.dto.ExtractionJobDto;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobWaitServiceTest {

    private final JobWaitProperties properties = new JobWaitProperties();
    private final IngestionJobUseCase ingestionJobs = mock(IngestionJobUseCase.class);
    private final ExtractionJobUseCase extractionJobs = mock(ExtractionJobUseCase.class);
    private JobWaitService service;

    @BeforeEach
    void setUp() {
        properties.setWaitTimeoutSeconds(1);
        properties.setPollIntervalMs(50);
        service = new JobWaitService(properties, ingestionJobs, extractionJobs);
    }

    @Test
    void ingestionReadyReturnsImmediatelyWhenMapped() {
        IngestionJob job = ingestionJob(JobStep.MAPPED, JobStatus.COMPLETED);
        IngestionJobDto dto = ingestionDto(job);
        when(ingestionJobs.get(7)).thenReturn(job);
        when(ingestionJobs.view(job)).thenReturn(dto);

        IngestionJobDto result = service.waitForIngestionView(7, JobStep.MAPPED);

        assertThat(result).isSameAs(dto);
        verify(ingestionJobs, times(1)).get(7);
    }

    @Test
    void ingestionWaitsUntilTargetStepReached() {
        IngestionJob running = ingestionJob(JobStep.PARSED, JobStatus.RUNNING);
        IngestionJob mapped = ingestionJob(JobStep.MAPPED, JobStatus.COMPLETED);
        IngestionJobDto dto = ingestionDto(mapped);
        when(ingestionJobs.get(7)).thenReturn(running, mapped);
        when(ingestionJobs.view(mapped)).thenReturn(dto);

        IngestionJobDto result = service.waitForIngestionView(7, JobStep.MAPPED);

        assertThat(result).isSameAs(dto);
        verify(ingestionJobs, times(2)).get(7);
    }

    @Test
    void ingestionReturnsImmediatelyOnFailure() {
        IngestionJob failed = ingestionJob(JobStep.PARSED, JobStatus.FAILED);
        IngestionJobDto dto = ingestionDto(failed);
        when(ingestionJobs.get(7)).thenReturn(failed);
        when(ingestionJobs.view(failed)).thenReturn(dto);

        assertThat(service.waitForIngestionView(7, JobStep.MAPPED)).isSameAs(dto);
    }

    @Test
    void ingestionTimesOutWhenStepNeverReached() {
        IngestionJob running = ingestionJob(JobStep.PARSED, JobStatus.RUNNING);
        when(ingestionJobs.get(7)).thenReturn(running);

        assertThatThrownBy(() -> service.waitForIngestionView(7, JobStep.MAPPED))
                .isInstanceOf(JobWaitTimeoutException.class)
                .hasMessageContaining("Ingestion job 7");
    }

    @Test
    void extractionReadyReturnsImmediatelyWhenCompleted() {
        ExtractionJob job = extractionJob(ExtractionJobStatus.COMPLETED);
        when(extractionJobs.get("abc")).thenReturn(job);
        when(extractionJobs.taskCounts("abc")).thenReturn(Map.of(ExtractionTaskStatus.COMPLETED, 3L));

        ExtractionJobDto result = service.waitForExtractionView("abc");

        assertThat(result.status()).isEqualTo(ExtractionJobStatus.COMPLETED);
        verify(extractionJobs, times(1)).get("abc");
    }

    @Test
    void extractionWaitsUntilTerminalStatus() {
        ExtractionJob running = extractionJob(ExtractionJobStatus.RUNNING);
        ExtractionJob done = extractionJob(ExtractionJobStatus.COMPLETED);
        when(extractionJobs.get("abc")).thenReturn(running, done);
        when(extractionJobs.taskCounts("abc")).thenReturn(Map.of());

        ExtractionJobDto result = service.waitForExtractionView("abc");

        assertThat(result.status()).isEqualTo(ExtractionJobStatus.COMPLETED);
        verify(extractionJobs, times(2)).get("abc");
    }

    @Test
    void extractionTimesOutWhileRunning() {
        ExtractionJob running = extractionJob(ExtractionJobStatus.RUNNING);
        when(extractionJobs.get("abc")).thenReturn(running);

        assertThatThrownBy(() -> service.waitForExtractionView("abc"))
                .isInstanceOf(JobWaitTimeoutException.class)
                .hasMessageContaining("Extraction job abc");
    }

    @Test
    void ingestionReadyHelperTreatsCancelledAsReady() {
        IngestionJob job = ingestionJob(JobStep.UPLOADED, JobStatus.CANCELLED);
        assertThat(JobWaitService.ingestionReady(job, JobStep.MAPPED)).isTrue();
    }

    @Test
    void completedAtMappedIsNotReadyForValidated() {
        IngestionJob mapped = ingestionJob(JobStep.MAPPED, JobStatus.COMPLETED);
        assertThat(JobWaitService.ingestionReady(mapped, JobStep.VALIDATED)).isFalse();
        assertThat(JobWaitService.ingestionReady(mapped, JobStep.MAPPED)).isTrue();
    }

    @Test
    void ingestionWaitsPastCompletedMappedUntilValidated() {
        IngestionJob mapped = ingestionJob(JobStep.MAPPED, JobStatus.COMPLETED);
        IngestionJob validated = ingestionJob(JobStep.VALIDATED, JobStatus.COMPLETED);
        IngestionJobDto dto = ingestionDto(validated);
        when(ingestionJobs.get(7)).thenReturn(mapped, validated);
        when(ingestionJobs.view(validated)).thenReturn(dto);

        IngestionJobDto result = service.waitForIngestionView(7, JobStep.VALIDATED);

        assertThat(result).isSameAs(dto);
        verify(ingestionJobs, times(2)).get(7);
    }

    @Test
    void extractionReadyHelperTreatsPausedAsReady() {
        ExtractionJob job = extractionJob(ExtractionJobStatus.PAUSED);
        assertThat(JobWaitService.extractionReady(job)).isTrue();
    }

    private static IngestionJob ingestionJob(JobStep step, JobStatus status) {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setCurrentStep(step);
        job.setStatus(status);
        return job;
    }

    private static IngestionJobDto ingestionDto(IngestionJob job) {
        return IngestionJobDto.from(job, java.util.List.of(), java.util.List.of("MAPPING"));
    }

    private static ExtractionJob extractionJob(ExtractionJobStatus status) {
        ExtractionJob job = new ExtractionJob();
        job.setId("abc");
        job.setStatus(status);
        return job;
    }
}
