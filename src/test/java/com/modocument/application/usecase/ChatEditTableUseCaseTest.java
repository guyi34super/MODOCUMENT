package com.modocument.application.usecase;

import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.prompt.ChatEditPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatEditTableUseCaseTest {

    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private final ChatEditTableUseCase useCase =
            new ChatEditTableUseCase(jobs, List.of(new ChatEditPrompt()), ai);

    private IngestionJob jobWithMappedRows() {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setMappedRowsJson("{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"ada\"}]}");
        job.setValidationResultJson("[]");
        job.setExportInfoJson("{}");
        when(jobs.getById(7)).thenReturn(Optional.of(job));
        when(jobs.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        return job;
    }

    @Test
    void appliesModifiedTableAndClearsStaleCheckpoints() {
        jobWithMappedRows();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TableData(List.of("FirstName"), List.of(Map.of("FirstName", "Ada"))));

        IngestionJob result = useCase.handle(7, "Capitalize all first names");

        assertThat(result.getMappedRowsJson()).contains("Ada");
        assertThat(result.getValidationResultJson()).isNull();
        assertThat(result.getExportInfoJson()).isNull();
        assertThat(result.getCurrentStep()).isEqualTo(JobStep.MAPPED);
        assertThat(result.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void rejectsBlankInstruction() {
        assertThatThrownBy(() -> useCase.handle(7, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyModelResult() {
        jobWithMappedRows();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TableData(List.of(), List.of()));

        assertThatThrownBy(() -> useCase.handle(7, "Do something"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty table");
    }
}
