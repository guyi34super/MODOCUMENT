package com.modocument.application.usecase;

import com.modocument.application.dto.ChatEditPromptInput;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.PromptStrategy;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Chat-based table editing: sends the user's instruction plus the job's
 * current table to the LLM and persists the returned (full) table back into
 * the job. Validation and export checkpoints are cleared because the data
 * changed.
 */
@Service
public class ChatEditTableUseCase {

    /** Keeps the prompt within a sane size; larger tables should be edited via the API. */
    public static final int MAX_ROWS = 500;

    private final IngestionJobRepository jobs;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;

    public ChatEditTableUseCase(IngestionJobRepository jobs,
                                List<PromptStrategy> promptStrategies,
                                AiFilesService aiFilesService) {
        this.jobs = jobs;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
    }

    public IngestionJob handle(int jobId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required.");
        }
        IngestionJob job = jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Ingestion job " + jobId + " not found."));
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job " + jobId + " is cancelled.");
        }

        boolean editMapped = job.getMappedRowsJson() != null;
        String tableJson = editMapped ? job.getMappedRowsJson() : job.getParsedTableJson();
        if (tableJson == null) {
            throw new IllegalStateException("Job has no table yet; run parse first.");
        }

        TableData current = IngestionJobUseCase.fromJson(tableJson, TableData.class);
        if (current.getRows() != null && current.getRows().size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Table has more than " + MAX_ROWS + " rows; chat editing is limited to "
                            + MAX_ROWS + " rows.");
        }

        PromptStrategy strategy = promptStrategies.stream()
                .filter(s -> s.getType().equals("ChatEdit"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ChatEdit prompt not registered."));
        String prompt = strategy.buildPrompt(new ChatEditPromptInput(instruction, tableJson));

        TableData modified = aiFilesService.sendForObject(
                prompt, new ParameterizedTypeReference<TableData>() {
                });
        if (modified == null || modified.isEmpty()) {
            throw new IllegalStateException("The model returned an empty table; nothing was changed.");
        }

        String modifiedJson = IngestionJobUseCase.toJson(modified);
        if (editMapped) {
            job.setMappedRowsJson(modifiedJson);
            job.setCurrentStep(JobStep.MAPPED);
        } else {
            job.setParsedTableJson(modifiedJson);
            job.setCurrentStep(JobStep.PARSED);
        }
        // Data changed: stale validation/export checkpoints no longer apply.
        job.setValidationResultJson(null);
        job.setExportInfoJson(null);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        return jobs.save(job);
    }
}
