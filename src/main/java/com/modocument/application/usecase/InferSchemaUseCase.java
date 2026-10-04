package com.modocument.application.usecase;

import com.modocument.application.dto.InferredSchema;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * LLM agent that suggests a template schema from a sample of extracted
 * records; the result can seed POST /Templates/CreateWithFields. Requires a
 * configured LLM key (surfaces as 409 like other LLM endpoints).
 */
@Service
public class InferSchemaUseCase {

    static final int SAMPLE_SIZE = 20;

    private final ExtractionJobStore jobs;
    private final ProcessedRecordStore processedRecords;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final ExtractionAuditLog audit;
    private final PiiTokenizer tokenizer;
    private final ExtractionLlmProperties extractionLlm;

    public InferSchemaUseCase(ExtractionJobStore jobs, ProcessedRecordStore processedRecords,
                              List<PromptStrategy> promptStrategies,
                              AiFilesService aiFilesService, ExtractionAuditLog audit,
                              PiiTokenizer tokenizer, ExtractionLlmProperties extractionLlm) {
        this.jobs = jobs;
        this.processedRecords = processedRecords;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.audit = audit;
        this.tokenizer = tokenizer;
        this.extractionLlm = extractionLlm;
    }

    public InferredSchema infer(String jobId) {
        ExtractionJob job = jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Extraction job " + jobId + " not found."));
        List<ProcessedRecord> sample = processedRecords.findBatch(jobId, null, SAMPLE_SIZE);
        if (sample.isEmpty()) {
            throw new IllegalStateException(
                    "Extraction job " + jobId + " has no processed records to infer from.");
        }
        // Sensitive sample values travel as placeholders; the inferred
        // schema's example values are detokenized back below.
        PiiTokenizer.Session session = tokenizer.newSession();
        List<Map<String, String>> sampleFields = sample.stream()
                .map(ProcessedRecord::getFields)
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
                "schema inference over " + sample.size() + " sample record(s)", null));
        if (schema == null || schema.fields() == null) {
            return schema;
        }
        return new InferredSchema(schema.fields().stream()
                .map(f -> new InferredSchema.InferredField(
                        f.name(), f.dataType(), session.detokenize(f.example())))
                .toList());
    }
}
