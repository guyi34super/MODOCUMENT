package com.modocument.application.usecase;

import com.modocument.application.dto.ExtractionSummaryPromptInput;
import com.modocument.application.dto.ExtractionSummaryResult;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.PromptStrategy;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.FallbackReason;
import com.modocument.infrastructure.ai.AiFailureClassifier;
import com.modocument.infrastructure.ai.AiPreflight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Writes a short human summary of an extraction run.
 *
 * <p>AI first, code fallback (plan §4): when {@link AiPreflight} says the AI
 * is switched off, not configured or behind an open circuit, or the call
 * fails (reason from {@link AiFailureClassifier}), or the reply is blank, the
 * summary is built from counters — records, tasks, per-field fill rates and
 * distinct counts ({@link ExtractionHeuristics#summarize}) — and the result
 * says {@code generatedBy: CODE} with the {@code fallbackReason}.
 */
@Service
public class SummarizeExtractionUseCase {

    private static final Logger log = LoggerFactory.getLogger(SummarizeExtractionUseCase.class);

    static final int SAMPLE_SIZE = 10;
    /** Records the code summary reads for fill rates and distinct counts. */
    static final int STATS_SCAN_LIMIT = 5_000;
    private static final int STATS_PAGE = 500;

    private final ExtractionJobStore jobs;
    private final ExtractionTaskQueue queue;
    private final ProcessedRecordStore processedRecords;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final ExtractionAuditLog audit;
    private final PiiTokenizer tokenizer;
    private final ExtractionLlmProperties extractionLlm;
    private final AiPreflight preflight;

    public SummarizeExtractionUseCase(ExtractionJobStore jobs, ExtractionTaskQueue queue,
                                      ProcessedRecordStore processedRecords,
                                      List<PromptStrategy> promptStrategies,
                                      AiFilesService aiFilesService, ExtractionAuditLog audit,
                                      PiiTokenizer tokenizer, ExtractionLlmProperties extractionLlm,
                                      AiPreflight preflight) {
        this.jobs = jobs;
        this.queue = queue;
        this.processedRecords = processedRecords;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.audit = audit;
        this.tokenizer = tokenizer;
        this.extractionLlm = extractionLlm;
        this.preflight = preflight;
    }

    public ExtractionSummaryResult summarize(String jobId) {
        ExtractionJob job = jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Extraction job " + jobId + " not found."));

        Map<?, Long> tasks = queue.countByStatus(jobId);
        long processed = processedRecords.countByJob(jobId);

        Optional<FallbackReason> skip = preflight.unavailableReason(job.getBankId());
        if (skip.isPresent()) {
            log.info("Run summary AI skipped ({}); summarising from counters", skip.get());
            return codeSummary(jobId, job, tasks, processed, skip.get());
        }
        try {
            String summary = summarizeWithAi(jobId, job, tasks, processed);
            if (summary == null || summary.isBlank()) {
                log.warn("Run summary AI returned nothing; summarising from counters");
                return codeSummary(jobId, job, tasks, processed, FallbackReason.INVALID_OUTPUT);
            }
            return ExtractionSummaryResult.ai(summary);
        } catch (RuntimeException aiFailure) {
            FallbackReason reason = AiFailureClassifier.classify(aiFailure);
            log.warn("Run summary AI failed ({}: {}); summarising from counters",
                    reason, aiFailure.getClass().getSimpleName());
            return codeSummary(jobId, job, tasks, processed, reason);
        }
    }

    /**
     * Sensitive sample values are tokenized before the prompt; detokenizing
     * the prose answer is best effort — a paraphrased placeholder ("the first
     * email") is not recoverable, but since the model never saw the raw value
     * the failure mode is a less specific summary, never a leak.
     */
    private String summarizeWithAi(String jobId, ExtractionJob job, Map<?, Long> tasks,
                                   long processed) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("status", job.getStatus());
        stats.put("counters", job.getCounters());
        stats.put("tasks", tasks);
        stats.put("processedRecords", processed);

        PiiTokenizer.Session session = tokenizer.newSession();
        List<Map<String, String>> sample = processedRecords.findBatch(jobId, null, SAMPLE_SIZE)
                .stream()
                .map(ProcessedRecord::getFields)
                .map(session::tokenizeRow)
                .toList();

        PromptStrategy strategy = PromptFactory.lookup(promptStrategies, "ExtractionSummary");
        String prompt = strategy.buildPrompt(new ExtractionSummaryPromptInput(
                IngestionJobUseCase.toJson(stats), IngestionJobUseCase.toJson(sample),
                job.getDataSpec()));
        String summary = session.detokenize(aiFilesService.sendPrompt(
                PromptEnvelope.builder(AiPurpose.EXTRACTION_SUMMARY)
                        .bank(job.getBankId())
                        .part(prompt, job.getBankId())
                        .model(extractionLlm.getModelId())
                        .build()));
        audit.add(ExtractionAuditEvent.of(jobId, job.getSourceId(), AuditAction.LLM_CALL, null,
                "run summary generated", null));
        return summary;
    }

    /** Counters plus a bounded scan of the processed records for fill and distinct counts. */
    private ExtractionSummaryResult codeSummary(String jobId, ExtractionJob job, Map<?, Long> tasks,
                                                long processed, FallbackReason reason) {
        ExtractionHeuristics.StatsCollector stats = new ExtractionHeuristics.StatsCollector();
        String after = null;
        boolean more = true;
        while (more && stats.records() < STATS_SCAN_LIMIT) {
            int want = (int) Math.min(STATS_PAGE, STATS_SCAN_LIMIT - stats.records());
            List<ProcessedRecord> page = processedRecords.findBatch(jobId, after, want);
            page.forEach(record -> stats.add(record.getFields()));
            more = page.size() == want;
            if (!page.isEmpty()) {
                after = page.get(page.size() - 1).getId();
            }
        }
        boolean partial = processed > stats.records();
        String status = job.getStatus() == null ? null : job.getStatus().name();
        return ExtractionSummaryResult.code(ExtractionHeuristics.summarize(
                status, job.getCounters(), tasks, processed, stats, partial, reason), reason);
    }
}
