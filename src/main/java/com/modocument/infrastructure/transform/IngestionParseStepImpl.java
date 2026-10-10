package com.modocument.infrastructure.transform;

import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractAndMapResult;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionMode;
import com.modocument.application.dto.TableExtractionPlan;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.dto.TableRowBatch;
import com.modocument.application.dto.TableRowsResult;
import com.modocument.application.dto.TableSchemaResult;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.TenantContext;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.infrastructure.ai.LlmStructuredJsonParser;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.StructuredTableMaterializer;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.TableExtractionBatchPlanner;
import com.modocument.infrastructure.file.TableExtractionHintsBuilder;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.prompt.TableExtractionPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parse stage of the ingestion pipeline. Every format is read to plain text
 * locally, then structured by the LLM (combined extract+map or schema + rows).
 */
@Service
public class IngestionParseStepImpl implements IngestionParseStep {

    private static final Logger log = LoggerFactory.getLogger(IngestionParseStepImpl.class);

    private final UploadStorage uploadStorage;
    private final TabularNormalizer tabularNormalizer;
    private final DocumentParser documentParser;
    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;
    private final PiiTokenizer tokenizer;
    private final ExcelParsingProperties excelParsing;
    private final ParsingProperties parsingProperties;
    private final TableExtractionHintsBuilder hintsBuilder;
    private final TableExtractionBatchPlanner batchPlanner;
    private final StructuredTableMaterializer structuredMaterializer;

    public IngestionParseStepImpl(UploadStorage uploadStorage,
                                  TabularNormalizer tabularNormalizer,
                                  DocumentParser documentParser,
                                  PromptFactory promptFactory,
                                  AiFilesService aiFilesService,
                                  PiiTokenizer tokenizer,
                                  ExcelParsingProperties excelParsing,
                                  ParsingProperties parsingProperties,
                                  TableExtractionHintsBuilder hintsBuilder,
                                  TableExtractionBatchPlanner batchPlanner,
                                  StructuredTableMaterializer structuredMaterializer) {
        this.uploadStorage = uploadStorage;
        this.tabularNormalizer = tabularNormalizer;
        this.documentParser = documentParser;
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
        this.tokenizer = tokenizer;
        this.excelParsing = excelParsing;
        this.parsingProperties = parsingProperties;
        this.hintsBuilder = hintsBuilder;
        this.batchPlanner = batchPlanner;
        this.structuredMaterializer = structuredMaterializer;
    }

    @Override
    public ParseResult parse(ParseContext context) {
        String fileName = context.fileName();
        Path file = uploadStorage.resolve(fileName);
        if (!Files.exists(file)) {
            throw new NoSuchElementException(
                    "File '" + fileName + "' not found in uploads folder.");
        }

        String extension = extensionOf(fileName);
        if (isExcel(extension) && !excelParsing.isLlmEnabled()) {
            ParseResult fast = new ParseResult(tabularNormalizer.normalize(file), Map.of());
            return fast;
        }

        var extraction = documentParser.extract(file);
        String text = extraction.text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from '" + fileName + "'.");
        }

        TableExtractionHints hints = hintsBuilder.build(
                file, text, extraction.extractionSource());
        TableExtractionPlan plan = batchPlanner.plan(text, hints, parsingProperties);

        PiiTokenizer.Session session = tokenizer.newSession();

        Optional<TableData> deterministic = Optional.empty();
        if (parsingProperties.isDeterministicEnabled()) {
            deterministic = structuredMaterializer.tryMaterialize(file, text, hints, plan);
        }
        if (deterministic.isPresent()) {
            TableData detokenized = new TableData(
                    deterministic.get().getColumns(),
                    session.detokenizeRows(deterministic.get().getRows()));
            return new ParseResult(detokenized, Map.of());
        }

        PromptStrategy strategy = promptFactory.getPrompt("TableExtraction");
        boolean combined = parsingProperties.isCombinedExtractionMapping()
                && useCombinedExtraction(context.templateFields());

        ParseResult extracted = plan.singlePass()
                ? extractSinglePass(strategy, session, plan.fullDocumentText(), hints,
                context.templateFields(), combined, file, extension)
                : extractBatched(strategy, session, plan, hints, context.templateFields(),
                combined, file, extension);

        if (extracted.table() == null || extracted.table().getRows() == null) {
            return extracted;
        }
        TableData table = TableColumnAligner.alignToHints(extracted.table(), hints);
        Map<String, String> mapping = extracted.suggestedMapping();
        if (table.getColumns() != null && extracted.table().getColumns() != null
                && !table.getColumns().equals(extracted.table().getColumns())) {
            mapping = TableColumnAligner.alignMappingKeys(
                    mapping, extracted.table().getColumns(), table.getColumns());
        }
        ParseResult result = new ParseResult(
                new TableData(table.getColumns(),
                        session.detokenizeRows(table.getRows())),
                mapping == null ? Map.of() : mapping);
        return result;
    }

    private static boolean useCombinedExtraction(List<TemplateFields> templateFields) {
        return templateFields != null && !templateFields.isEmpty();
    }

    private ParseResult extractSinglePass(PromptStrategy strategy, PiiTokenizer.Session session,
                                          String text, TableExtractionHints hints,
                                          List<TemplateFields> templateFields, boolean combined,
                                          Path file, String extension) {
        String tokenizedText = session.tokenizeText(text);
        if (combined && parsingProperties.isCombinedExtractionMapping()) {
            String prompt = strategy.buildPrompt(new TableExtractionPromptInput(
                    tokenizedText, hints, TableExtractionMode.EXTRACT_AND_MAP,
                    null, null, templateFields));
            TableExtractAndMapResult result = extractExtractAndMapWithRetry(
                    prompt, hints, templateFieldNames(templateFields), file, extension);
            return toParseResult(result);
        }
        String prompt = strategy.buildPrompt(
                new TableExtractionPromptInput(tokenizedText, hints, TableExtractionMode.FULL, null, null));
        return new ParseResult(extractWithRetry(prompt, hints, file, extension), Map.of());
    }

    private ParseResult extractBatched(PromptStrategy strategy, PiiTokenizer.Session session,
                                       TableExtractionPlan plan, TableExtractionHints hints,
                                       List<TemplateFields> templateFields, boolean combined,
                                       Path file, String extension) {
        if (combined && parsingProperties.isCombinedExtractionMapping()) {
            return extractCombinedBatched(strategy, session, plan, hints, templateFields, file, extension);
        }

        List<String> columns = resolveColumnsViaLlm(strategy, session, plan, hints, file, extension);

        log.info("Batched LLM extraction: {} columns, {} batches (~{} rows), concurrency {}",
                columns.size(), plan.batches().size(), plan.estimatedDataRows(),
                parsingProperties.getLlmBatchConcurrency());

        List<Map<String, String>> allRows = extractBatchesParallel(
                strategy, session, plan.batches(), columns, hints, file, extension);

        if (hints != null && hints.dataRowCount() > 0
                && allRows.size() != hints.dataRowCount()) {
            log.warn("Batched extraction returned {} rows but hints expect {}",
                    allRows.size(), hints.dataRowCount());
        }
        return new ParseResult(new TableData(columns, allRows), Map.of());
    }

    private ParseResult extractCombinedBatched(PromptStrategy strategy, PiiTokenizer.Session session,
                                               TableExtractionPlan plan, TableExtractionHints hints,
                                               List<TemplateFields> templateFields,
                                               Path file, String extension) {
        List<TableRowBatch> batches = plan.batches();
        if (batches.isEmpty()) {
            return new ParseResult(new TableData(List.of(), List.of()), Map.of());
        }

        TableRowBatch firstBatch = batches.get(0);
        TableExtractAndMapResult firstResult = extractExtractAndMapBatchWithRetry(
                strategy, session, firstBatch, hints, templateFields, file, extension);
        List<String> columns = firstResult.getColumns();
        Map<String, String> suggestedMapping = firstResult.getMapping() == null
                ? Map.of() : new LinkedHashMap<>(firstResult.getMapping());

        log.info("Combined batched extraction: {} columns, {} batches (~{} rows), concurrency {}",
                columns.size(), batches.size(), plan.estimatedDataRows(),
                parsingProperties.getLlmBatchConcurrency());

        List<Map<String, String>> allRows = new ArrayList<>(
                firstResult.getRows() == null ? List.of() : firstResult.getRows());
        if (batches.size() > 1) {
            allRows.addAll(extractBatchesParallel(
                    strategy, session, batches.subList(1, batches.size()),
                    columns, hints, file, extension));
        }

        if (hints != null && hints.dataRowCount() > 0
                && allRows.size() != hints.dataRowCount()) {
            log.warn("Combined batched extraction returned {} rows but hints expect {}",
                    allRows.size(), hints.dataRowCount());
        }
        return new ParseResult(new TableData(columns, allRows), suggestedMapping);
    }

    private List<Map<String, String>> extractBatchesParallel(PromptStrategy strategy,
                                                              PiiTokenizer.Session session,
                                                              List<TableRowBatch> batches,
                                                              List<String> columns,
                                                              TableExtractionHints hints,
                                                              Path file, String extension) {
        if (batches.isEmpty()) {
            return List.of();
        }
        int concurrency = Math.max(1, parsingProperties.getLlmBatchConcurrency());
        if (concurrency == 1 || batches.size() == 1) {
            List<Map<String, String>> allRows = new ArrayList<>();
            for (TableRowBatch batch : batches) {
                allRows.addAll(extractRowBatchWithRetry(
                        strategy, session, batch, columns, hints, file, extension));
            }
            return allRows;
        }

        // Session is not thread-safe; tokenize every batch on this thread before
        // fanning out LLM calls, which are the slow part worth parallelizing.
        List<String> tokenizedBatchTexts = new ArrayList<>(batches.size());
        for (TableRowBatch batch : batches) {
            tokenizedBatchTexts.add(session.tokenizeText(batch.rowText()));
        }

        // The AI gateway refuses any prompt whose thread has no bound tenant
        // (C9's one-bank rule), so the submitting thread's context travels
        // onto the plain pool explicitly.
        TenantContext.Snapshot tenant = TenantContext.snapshot();

        @SuppressWarnings("unchecked")
        List<Map<String, String>>[] ordered = new List[batches.size()];
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(concurrency, batches.size()))) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < batches.size(); i++) {
                TableRowBatch batch = batches.get(i);
                String tokenizedBatch = tokenizedBatchTexts.get(i);
                int slot = i;
                futures.add(CompletableFuture.runAsync(
                        () -> TenantContext.runWith(tenant, () -> {
                            List<Map<String, String>> batchRows = extractRowBatchWithRetry(
                                    strategy, tokenizedBatch, batch, columns, hints, file, extension);
                            ordered[slot] = batchRows;
                        }), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        List<Map<String, String>> allRows = new ArrayList<>();
        for (List<Map<String, String>> batchRows : ordered) {
            if (batchRows != null) {
                allRows.addAll(batchRows);
            }
        }
        return allRows;
    }

    private List<String> resolveColumnsViaLlm(PromptStrategy strategy, PiiTokenizer.Session session,
                                              TableExtractionPlan plan, TableExtractionHints hints,
                                              Path file, String extension) {
        if (hints != null && hints.candidateHeaderCells() != null
                && !hints.candidateHeaderCells().isEmpty()
                && hints.expectedColumnCount() == hints.candidateHeaderCells().size()) {
            return hints.candidateHeaderCells();
        }
        String schemaText = buildSchemaText(plan);
        String tokenizedSchema = session.tokenizeText(schemaText);
        String schemaPrompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedSchema, hints, TableExtractionMode.SCHEMA, null, null));
        return extractSchemaWithRetry(schemaPrompt, hints, file, extension);
    }

    private static String buildSchemaText(TableExtractionPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.headerText() != null && !plan.headerText().isBlank()) {
            sb.append(plan.headerText()).append(System.lineSeparator());
        }
        for (String sample : plan.sampleRows()) {
            sb.append(sample).append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    private List<String> extractSchemaWithRetry(String prompt, TableExtractionHints hints,
                                                Path file, String extension) {
        TableSchemaResult schema;
        try {
            schema = callLlmForSchema(prompt);
        } catch (RuntimeException e) {
            throw llmParseFailure("schema extraction", e);
        }
        if (schema == null || schema.getColumns() == null || schema.getColumns().isEmpty()) {
            throw new IllegalStateException("LLM schema extraction returned no columns.");
        }
        List<String> columns = schema.getColumns();
        if (hints != null && hints.expectedColumnCount() > 0
                && columns.size() < hints.expectedColumnCount()) {
            log.warn("Schema extraction returned {} columns but hints expect {}; retrying once",
                    columns.size(), hints.expectedColumnCount());
            String retryPrompt = prompt + TableExtractionPrompt.correctiveSuffix(
                    columns.size(), hints.expectedColumnCount());
            try {
                TableSchemaResult retry = callLlmForSchema(retryPrompt);
                if (retry != null && retry.getColumns() != null
                        && retry.getColumns().size() >= columns.size()) {
                    return retry.getColumns();
                }
            } catch (RuntimeException e) {
                throw llmParseFailure("schema extraction retry", e);
            }
        }
        return columns;
    }

    private TableExtractAndMapResult extractExtractAndMapBatchWithRetry(
            PromptStrategy strategy, PiiTokenizer.Session session, TableRowBatch batch,
            TableExtractionHints hints, List<TemplateFields> templateFields,
            Path file, String extension) {
        String tokenizedBatch = session.tokenizeText(batch.rowText());
        String prompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedBatch, hints, TableExtractionMode.EXTRACT_AND_MAP,
                null, batch, templateFields));
        return extractExtractAndMapWithRetry(
                prompt, hints, templateFieldNames(templateFields), file, extension);
    }

    private TableExtractAndMapResult extractExtractAndMapWithRetry(
            String prompt, TableExtractionHints hints,
            List<String> templateFieldNames, Path file, String extension) {
        TableExtractAndMapResult extracted;
        try {
            extracted = callLlmForExtractAndMap(prompt, templateFieldNames);
        } catch (RuntimeException first) {
            log.warn("Combined extraction JSON parse failed; retrying once: {}", first.getMessage());
            String retryPrompt = prompt + TableExtractionPrompt.jsonParseCorrectiveSuffix(first.getMessage());
            try {
                extracted = callLlmForExtractAndMap(retryPrompt, templateFieldNames);
            } catch (RuntimeException second) {
                throw llmParseFailure("combined extraction", second);
            }
        }
        if (extracted == null || extracted.getColumns() == null || extracted.getColumns().isEmpty()) {
            throw new IllegalStateException("LLM combined extraction returned no columns.");
        }
        if (extracted.getRows() == null || extracted.getRows().isEmpty()) {
            throw new IllegalStateException("LLM combined extraction returned no rows.");
        }

        if (hints != null && hints.expectedColumnCount() > 0
                && extracted.getColumns().size() < hints.expectedColumnCount()) {
            log.warn("Combined extraction returned {} columns but hints expect {}; retrying once",
                    extracted.getColumns().size(), hints.expectedColumnCount());
            String retryPrompt = prompt + TableExtractionPrompt.correctiveSuffix(
                    extracted.getColumns().size(), hints.expectedColumnCount());
            try {
                TableExtractAndMapResult retry = callLlmForExtractAndMap(retryPrompt, templateFieldNames);
                if (retry != null && retry.getColumns() != null
                        && retry.getColumns().size() >= extracted.getColumns().size()
                        && retry.getRows() != null && !retry.getRows().isEmpty()) {
                    return retry;
                }
            } catch (RuntimeException e) {
                throw llmParseFailure("combined extraction retry", e);
            }
        }
        return extracted;
    }

    private static ParseResult toParseResult(TableExtractAndMapResult result) {
        Map<String, String> mapping = result.getMapping() == null
                ? Map.of() : new LinkedHashMap<>(result.getMapping());
        return new ParseResult(
                new TableData(result.getColumns(), result.getRows()),
                mapping);
    }

    private List<Map<String, String>> extractRowBatchWithRetry(PromptStrategy strategy,
                                                                PiiTokenizer.Session session,
                                                                TableRowBatch batch,
                                                                List<String> columns,
                                                                TableExtractionHints hints,
                                                                Path file, String extension) {
        return extractRowBatchWithRetry(strategy, session.tokenizeText(batch.rowText()),
                batch, columns, hints, file, extension);
    }

    private List<Map<String, String>> extractRowBatchWithRetry(PromptStrategy strategy,
                                                                String tokenizedBatch,
                                                                TableRowBatch batch,
                                                                List<String> columns,
                                                                TableExtractionHints hints,
                                                                Path file, String extension) {
        String batchPrompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedBatch, hints, TableExtractionMode.ROW_BATCH, columns, batch));
        int expectedRows = batch.endRow() - batch.startRow() + 1;
        try {
            TableRowsResult result = callLlmForRows(batchPrompt, columns);
            if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
                return result.getRows();
            }
        } catch (RuntimeException first) {
            log.warn("Batch {} JSON parse failed; retrying once: {}",
                    batch.batchIndex(), first.getMessage());
            String retryPrompt = batchPrompt
                    + TableExtractionPrompt.jsonParseCorrectiveSuffix(first.getMessage());
            try {
                TableRowsResult retry = callLlmForRows(retryPrompt, columns);
                if (retry != null && retry.getRows() != null && !retry.getRows().isEmpty()) {
                    return retry.getRows();
                }
            } catch (RuntimeException second) {
                throw llmParseFailure("batch " + batch.batchIndex()
                        + " of " + batch.totalBatches()
                        + " (rows " + batch.startRow() + "–" + batch.endRow() + ")", second);
            }
        }
        throw new IllegalStateException(
                "LLM table extraction returned no rows for batch " + batch.batchIndex()
                        + " of " + batch.totalBatches()
                        + " (rows " + batch.startRow() + "–" + batch.endRow() + ").");
    }

    private TableData extractWithRetry(String prompt, TableExtractionHints hints,
                                       Path file, String extension) {
        TableData extracted;
        try {
            extracted = callLlmForTable(prompt);
        } catch (RuntimeException e) {
            throw llmParseFailure("single-pass extraction", e);
        }

        if (hints == null || hints.expectedColumnCount() <= 0 || extracted == null
                || extracted.getColumns() == null) {
            return extracted;
        }
        int expected = hints.expectedColumnCount();
        int returned = extracted.getColumns().size();
        if (returned >= expected) {
            return extracted;
        }
        log.warn("Table extraction returned {} columns but hints expect {}; retrying once",
                returned, expected);
        String retryPrompt = prompt + TableExtractionPrompt.correctiveSuffix(returned, expected);
        try {
            TableData retry = callLlmForTable(retryPrompt);
            if (retry != null && retry.getColumns() != null
                    && retry.getColumns().size() >= returned) {
                return retry;
            }
        } catch (RuntimeException e) {
            throw llmParseFailure("single-pass extraction retry", e);
        }
        log.warn("Retry still returned insufficient columns; using first result");
        return extracted;
    }

    private static IllegalStateException llmParseFailure(String phase, RuntimeException cause) {
        return new IllegalStateException(
                "LLM table extraction failed during " + phase + ": " + cause.getMessage(), cause);
    }

    private TableData callLlmForTable(String prompt) {
        return aiFilesService.sendForObject(
                PromptEnvelope.of(AiPurpose.TABLE_EXTRACTION, prompt),
                new ParameterizedTypeReference<TableData>() {
                });
    }

    private TableSchemaResult callLlmForSchema(String prompt) {
        String raw = aiFilesService.sendPrompt(PromptEnvelope.of(AiPurpose.TABLE_EXTRACTION, prompt));
        return LlmStructuredJsonParser.parseSchema(raw);
    }

    private TableRowsResult callLlmForRows(String prompt, List<String> columns) {
        String raw = aiFilesService.sendPrompt(PromptEnvelope.of(AiPurpose.TABLE_EXTRACTION, prompt));
        return LlmStructuredJsonParser.parseRows(raw, columns);
    }

    private TableExtractAndMapResult callLlmForExtractAndMap(String prompt,
                                                             List<String> templateFieldNames) {
        String raw = aiFilesService.sendPrompt(PromptEnvelope.of(AiPurpose.TABLE_EXTRACTION, prompt));
        return LlmStructuredJsonParser.parseExtractAndMap(raw, templateFieldNames);
    }

    private static List<String> templateFieldNames(List<TemplateFields> templateFields) {
        return templateFields.stream().map(TemplateFields::getFieldName).toList();
    }

    private static boolean isExcel(String extension) {
        return extension.equals(".xlsx") || extension.equals(".xls") || extension.equals(".xlsm");
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
