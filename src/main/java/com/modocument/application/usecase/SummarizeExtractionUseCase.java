package com.modocument.application.usecase;

import com.modocument.application.dto.ExtractionSummaryPromptInput;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.model.AuditAction;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** LLM agent that writes a short human summary of an extraction run. */
@Service
public class SummarizeExtractionUseCase {

    static final int SAMPLE_SIZE = 10;

    private final ExtractionJobStore jobs;
    private final ExtractionTaskQueue queue;
    private final ProcessedRecordStore processedRecords;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final ExtractionAuditLog audit;
    private final PiiTokenizer tokenizer;
    private final ExtractionLlmProperties extractionLlm;

    public SummarizeExtractionUseCase(ExtractionJobStore jobs, ExtractionTaskQueue queue,
                                      ProcessedRecordStore processedRecords,
                                      List<PromptStrategy> promptStrategies,
                                      AiFilesService aiFilesService, ExtractionAuditLog audit,
                                      PiiTokenizer tokenizer, ExtractionLlmProperties extractionLlm) {
        this.jobs = jobs;
        this.queue = queue;
        this.processedRecords = processedRecords;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.audit = audit;
        this.tokenizer = tokenizer;
        this.extractionLlm = extractionLlm;
    }

    /**
     * Sensitive sample values are tokenized before the prompt; detokenizing
     * the prose answer is best effort — a paraphrased placeholder ("the first
     * email") is not recoverable, but since the model never saw the raw value
     * the failure mode is a less specific summary, never a leak.
     */
    public String summarize(String jobId) {
        ExtractionJob job = jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Extraction job " + jobId + " not found."));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("status", job.getStatus());
        stats.put("counters", job.getCounters());
        stats.put("tasks", queue.countByStatus(jobId));
        stats.put("processedRecords", processedRecords.countByJob(jobId));

        PiiTokenizer.Session session = tokenizer.newSession();
        List<Map<String, String>> sample = processedRecords.findBatch(jobId, null, SAMPLE_SIZE)
                .stream()
                .map(ProcessedRecord::getFields)
                .map(session::tokenizeRow)
                .toList();

        PromptStrategy strategy = promptStrategies.stream()
                .filter(s -> s.getType().equals("ExtractionSummary"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ExtractionSummary prompt not registered."));
        String prompt = strategy.buildPrompt(new ExtractionSummaryPromptInput(
                IngestionJobUseCase.toJson(stats), IngestionJobUseCase.toJson(sample),
                job.getDataSpec()));
        String summary = session.detokenize(
                aiFilesService.sendPrompt(prompt, extractionLlm.getModelId()));
        audit.add(ExtractionAuditEvent.of(jobId, job.getSourceId(), AuditAction.LLM_CALL, null,
                "run summary generated", null));
        return summary;
    }
}
