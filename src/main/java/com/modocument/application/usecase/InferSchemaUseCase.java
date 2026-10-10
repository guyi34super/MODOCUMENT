package com.modocument.application.usecase;

import com.modocument.application.dto.InferredSchema;
import com.modocument.application.dto.InferredSchemaResult;
import com.modocument.application.dto.SchemaInferencePromptInput;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.PromptStrategy;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Suggests a template schema from a sample of extracted records; the result
 * can seed POST /Templates/CreateWithFields.
 *
 * <p>AI first, code fallback (plan §4): when {@link AiPreflight} says the AI
 * is switched off, not configured or behind an open circuit, or the call
 * fails (reason from {@link AiFailureClassifier}), or the model returns no
 * fields, the schema is inferred from the sample values by
 * {@link ExtractionHeuristics#inferSchema} and the result says
 * {@code generatedBy: CODE} with the {@code fallbackReason}.
 */
@Service
public class InferSchemaUseCase {

    private static final Logger log = LoggerFactory.getLogger(InferSchemaUseCase.class);

    static final int SAMPLE_SIZE = 20;

    private final ExtractionJobStore jobs;
    private final ProcessedRecordStore processedRecords;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final ExtractionAuditLog audit;
    private final PiiTokenizer tokenizer;
    private final ExtractionLlmProperties extractionLlm;
    private final AiPreflight preflight;

    public InferSchemaUseCase(ExtractionJobStore jobs, ProcessedRecordStore processedRecords,
                              List<PromptStrategy> promptStrategies,
                              AiFilesService aiFilesService, ExtractionAuditLog audit,
                              PiiTokenizer tokenizer, ExtractionLlmProperties extractionLlm,
                              AiPreflight preflight) {
        this.jobs = jobs;
        this.processedRecords = processedRecords;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.audit = audit;
        this.tokenizer = tokenizer;
        this.extractionLlm = extractionLlm;
        this.preflight = preflight;
    }

    public InferredSchemaResult infer(String jobId) {
        ExtractionJob job = jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Extraction job " + jobId + " not found."));
        List<ProcessedRecord> sample = processedRecords.findBatch(jobId, null, SAMPLE_SIZE);
        if (sample.isEmpty()) {
            throw new IllegalStateException(
                    "Extraction job " + jobId + " has no processed records to infer from.");
        }
        List<Map<String, String>> rows = sample.stream().map(ProcessedRecord::getFields).toList();

        Optional<FallbackReason> skip = preflight.unavailableReason(job.getBankId());
        if (skip.isPresent()) {
            log.info("Schema inference AI skipped ({}); inferring from sample values", skip.get());
            return InferredSchemaResult.code(ExtractionHeuristics.inferSchema(rows), skip.get());
        }
        try {
            List<InferredSchema.InferredField> fields = inferWithAi(jobId, job, rows);
            if (fields == null || fields.isEmpty()) {
                log.warn("Schema inference AI returned no fields; inferring from sample values");
                return InferredSchemaResult.code(ExtractionHeuristics.inferSchema(rows),
                        FallbackReason.INVALID_OUTPUT);
            }
            return InferredSchemaResult.ai(fields);
        } catch (RuntimeException aiFailure) {
            FallbackReason reason = AiFailureClassifier.classify(aiFailure);
            log.warn("Schema inference AI failed ({}: {}); inferring from sample values",
                    reason, aiFailure.getClass().getSimpleName());
            return InferredSchemaResult.code(ExtractionHeuristics.inferSchema(rows), reason);
        }
    }

    private List<InferredSchema.InferredField> inferWithAi(String jobId, ExtractionJob job,
                                                          List<Map<String, String>> rows) {
        // Sensitive sample values travel as placeholders; the inferred
        // schema's example values are detokenized back below.
        PiiTokenizer.Session session = tokenizer.newSession();
        List<Map<String, String>> sampleFields = rows.stream()
                .map(session::tokenizeRow)
                .toList();

        PromptStrategy strategy = promptStrategies.stream()
                .filter(s -> s.getType().equals("SchemaInference"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "SchemaInference prompt not registered."));
        String prompt = strategy.buildPrompt(new SchemaInferencePromptInput(
                IngestionJobUseCase.toJson(sampleFields), job.getDataSpec()));
        InferredSchema schema = aiFilesService.sendForObject(
                PromptEnvelope.builder(AiPurpose.SCHEMA_INFERENCE)
                        .bank(job.getBankId())
                        .part(prompt, job.getBankId())
                        .model(extractionLlm.getModelId())
                        .build(),
                new ParameterizedTypeReference<InferredSchema>() {
                });
        audit.add(ExtractionAuditEvent.of(jobId, job.getSourceId(), AuditAction.LLM_CALL, null,
                "schema inference over " + rows.size() + " sample record(s)", null));
        if (schema == null || schema.fields() == null) {
            return null;
        }
        return schema.fields().stream()
                .map(f -> new InferredSchema.InferredField(
                        f.name(), f.dataType(), session.detokenize(f.example())))
                .toList();
    }
}
