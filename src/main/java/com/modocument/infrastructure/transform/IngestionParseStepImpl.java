package com.modocument.infrastructure.transform;

import com.modocument.application.dto.DocumentExtractionResult;
import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseProvenance;
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
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;
import com.modocument.infrastructure.ai.AiClientProperties;
import com.modocument.infrastructure.ai.AiErrorMessages;
import com.modocument.infrastructure.ai.AiFailureClassifier;
import com.modocument.infrastructure.ai.AiNotConfiguredException;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.ai.LlmExtractionFailedException;
import com.modocument.infrastructure.ai.LlmStructuredJsonParser;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.ParsingSettings;
import com.modocument.infrastructure.file.StructuredTableMaterializer;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.TableExtractionBatchPlanner;
import com.modocument.infrastructure.file.TableExtractionHintsBuilder;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.prompt.TableExtractionPrompt;
import com.modocument.infrastructure.transform.code.CodeExtraction;
import com.modocument.infrastructure.transform.code.CodeTableExtractor;
import com.modocument.infrastructure.transform.code.ExtractionInput;
import com.modocument.infrastructure.transform.code.FieldSpec;
import com.modocument.infrastructure.transform.code.TextDecoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Parse stage of the ingestion pipeline. Every format is read to plain text
 * locally; well-structured files are materialized deterministically, the rest
 * is structured by the LLM (combined extract+map or schema + rows) — or, by
 * the template's / bank's {@link ExtractionMode} (ws23), by code:
 *
 * <ul>
 *   <li><b>CODE_ONLY</b> — never calls the AI: the Excel fast path, the strict
 *       {@link StructuredTableMaterializer}, then the {@link CodeTableExtractor}
 *       (scores from 0.40 pass as low confidence).</li>
 *   <li><b>AI_ONLY</b> — the pre-phase-2 behaviour: an AI failure fails the job.</li>
 *   <li><b>AI_WITH_CODE_FALLBACK</b> (default) — the same fast paths, then
 *       {@link AiPreflight} (AI switched off, not configured, circuit open,
 *       quota spent) and the LLM; any of those failing hands the document to
 *       the code extractor, with the reason from {@link AiFailureClassifier}.
 *       In batched extraction a failed batch is re-parsed by code with the
 *       known columns and, once the AI time budget is spent, the remaining
 *       batches go to code too (AI_PARTIAL). If code cannot reach the
 *       confidence threshold either, the job fails naming both failures.</li>
 * </ul>
 *
 * Still fatal in every mode: a missing file, and a document with no
 * extractable text. Every result carries its {@link ParseProvenance}.
 */
@Service
public class IngestionParseStepImpl implements IngestionParseStep {

    private static final Logger log = LoggerFactory.getLogger(IngestionParseStepImpl.class);

    /** Formats read as plain text, so a non-UTF-8 file can be re-decoded (windows-1252). */
    private static final Set<String> PLAIN_TEXT = Set.of(".csv", ".txt", ".tsv", ".text");

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
    private final CodeParseSupport codeParse;
    private final ExtractionModeResolver modes;
    private final AiPreflight preflight;
    private final AiClientProperties aiClient;
    /** Null = the yml parsing properties only (unit-test wiring). */
    private final ParsingSettings parsingSettings;

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
                                  StructuredTableMaterializer structuredMaterializer,
                                  CodeParseSupport codeParse,
                                  ExtractionModeResolver modes,
                                  AiPreflight preflight,
                                  AiClientProperties aiClient,
                                  ParsingSettings parsingSettings) {
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
        this.codeParse = codeParse;
        this.modes = modes;
        this.preflight = preflight;
        this.aiClient = aiClient;
        this.parsingSettings = parsingSettings;
    }

    @Override
    public ParseResult parse(ParseContext context) {
        String fileName = context.fileName();
        Path file = uploadStorage.resolve(fileName);
        if (!Files.exists(file)) {
            throw new NoSuchElementException(
                    "File '" + fileName + "' not found in uploads folder.");
        }
        Integer bankId = context.bankId() != null
                ? context.bankId() : TenantContext.currentBankId().orElse(null);
        ExtractionMode mode = modes.resolve(context.extractionMode(), bankId);
        Run run = new Run(context, file, extensionOf(fileName), bankId, mode);
        return switch (mode) {
            case CODE_ONLY -> parseCodeOnly(run);
            case AI_ONLY -> parseAiOnly(run);
            case AI_WITH_CODE_FALLBACK -> parseWithFallback(run);
        };
    }

    // ---- modes ---------------------------------------------------------------

    /** The pre-phase-2 pipeline: fast paths, then the LLM; an AI failure is the job's failure. */
    private ParseResult parseAiOnly(Run run) {
        if (isExcel(run.extension) && !run.excel.isLlmEnabled()) {
            return excelFastPath(run, null);
        }
        DocumentExtractionResult extraction = documentParser.extract(run.file);
        String text = requireText(extraction.text(), run);
        TableExtractionHints hints = hintsBuilder.build(run.file, text, extraction.extractionSource());
        TableExtractionPlan plan = batchPlanner.plan(text, hints, run.parsing);

        PiiTokenizer.Session session = tokenizer.newSession();
        Optional<ParseResult> deterministic = deterministic(run, session, text, hints, plan, null);
        if (deterministic.isPresent()) {
            return deterministic.get();
        }
        return extractWithLlm(run, session, text, hints, plan);
    }

    /** AI first, code whenever the AI is skipped or fails (the default mode). */
    private ParseResult parseWithFallback(Run run) {
        if (isExcel(run.extension) && !run.excel.isLlmEnabled()) {
            return excelFastPath(run, null);
        }
        DocumentExtractionResult extraction = readDocument(run);
        String text = requireText(extraction.text(), run);
        TableExtractionHints hints = safeHints(run, text, extraction.extractionSource());
        TableExtractionPlan plan = batchPlanner.plan(text, hints, run.parsing);

        PiiTokenizer.Session session = tokenizer.newSession();
        Optional<ParseResult> deterministic = deterministic(run, session, text, hints, plan, null);
        if (deterministic.isPresent()) {
            return deterministic.get();
        }

        Optional<FallbackReason> skip = preflight.check(run.bankId, run.context.ownerUserId());
        if (skip.isPresent()) {
            log.info("AI extraction skipped ({}); parsing '{}' with code", skip.get(), run.extension);
            return codeFallback(run, text, hints, skip.get(), null);
        }
        try {
            return extractWithLlm(run, session, text, hints, plan);
        } catch (RuntimeException aiFailure) {
            FallbackReason reason = AiFailureClassifier.classify(aiFailure);
            if (reason == FallbackReason.PROMPT_REFUSED) {
                // Privacy-safe: the refused prompt never left the backend.
                log.warn("AI prompt refused by the gateway's privacy checks; parsing '{}' with code "
                        + "instead (no data left the backend)", run.extension);
            } else {
                log.warn("AI extraction failed ({}: {}); falling back to code extraction",
                        reason, aiFailure.getClass().getSimpleName());
            }
            return codeFallback(run, text, hints, reason, aiFailure);
        }
    }

    /** Never touches the AI: Excel fast path, structured materializer, code extractor. */
    private ParseResult parseCodeOnly(Run run) {
        if (isExcel(run.extension)) {
            return excelFastPath(run, FallbackReason.MODE_CODE_ONLY);
        }
        DocumentExtractionResult extraction = readDocument(run);
        String text = requireText(extraction.text(), run);
        TableExtractionHints hints = safeHints(run, text, extraction.extractionSource());
        TableExtractionPlan plan = batchPlanner.plan(text, hints, run.parsing);

        Optional<ParseResult> deterministic =
                deterministic(run, null, text, hints, plan, FallbackReason.MODE_CODE_ONLY);
        if (deterministic.isPresent()) {
            return deterministic.get();
        }
        CodeParseSupport.Outcome outcome = codeParse.extract(
                codeInput(run, text, hints), run.minConfidence, true);
        if (outcome.accepted().isPresent()) {
            return codeResult(outcome.accepted().get(), FallbackReason.MODE_CODE_ONLY);
        }
        throw new IllegalStateException("Code-based extraction could not find a table in '"
                + run.context.fileName() + "' (" + outcome.describeBest()
                + "; code-only mode accepts " + String.format(Locale.ROOT, "%.2f",
                CodeTableExtractor.CODE_ONLY_FLOOR) + " and above).");
    }

    // ---- shared steps ----------------------------------------------------------

    private ParseResult excelFastPath(Run run, FallbackReason reason) {
        TableData table = tabularNormalizer.normalize(run.file);
        return new ParseResult(table, Map.of(),
                ParseProvenance.code(ParseProvenance.NATIVE_SHEET, null, reason));
    }

    private Optional<ParseResult> deterministic(Run run, PiiTokenizer.Session session, String text,
                                                TableExtractionHints hints, TableExtractionPlan plan,
                                                FallbackReason reason) {
        if (!run.parsing.isDeterministicEnabled()) {
            return Optional.empty();
        }
        Optional<TableData> table = structuredMaterializer.tryMaterialize(run.file, text, hints, plan);
        if (table.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, String>> rows = session == null
                ? table.get().getRows() : session.detokenizeRows(table.get().getRows());
        return Optional.of(new ParseResult(new TableData(table.get().getColumns(), rows), Map.of(),
                ParseProvenance.code(ParseProvenance.STRUCTURED, null, reason)));
    }

    /** The document's text; a CSV/TXT the UTF-8 reader refuses is re-decoded (windows-1252). */
    private DocumentExtractionResult readDocument(Run run) {
        try {
            return documentParser.extract(run.file);
        } catch (UncheckedIOException unreadable) {
            if (!PLAIN_TEXT.contains(run.extension)) {
                throw unreadable;
            }
            try {
                TextDecoding.Decoded decoded = TextDecoding.decode(Files.readAllBytes(run.file));
                log.info("Read '{}' as {} after the UTF-8 reader failed",
                        run.extension, decoded.charset());
                return new DocumentExtractionResult(decoded.text(), "local");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The table pre-scan, or empty hints when it cannot read the file (e.g. a cp1252 CSV). */
    private TableExtractionHints safeHints(Run run, String text, String source) {
        try {
            return hintsBuilder.build(run.file, text, source);
        } catch (UncheckedIOException e) {
            log.debug("Table pre-scan could not read '{}': {}", run.extension, e.getMessage());
            return TableExtractionHints.empty(run.extension, source);
        }
    }

    private static String requireText(String text, Run run) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from '" + run.context.fileName() + "'.");
        }
        return text;
    }

    private ExtractionInput codeInput(Run run, String text, TableExtractionHints hints) {
        return codeParse.input(run.context.templateId(),
                CodeParseSupport.fieldSpecs(run.context.templateFields()), text, run.file, hints);
    }

    /**
     * The document parsed by code in place of the AI; when code cannot reach
     * the threshold either, the job fails with both reasons.
     */
    private ParseResult codeFallback(Run run, String text, TableExtractionHints hints,
                                     FallbackReason reason, RuntimeException aiFailure) {
        CodeParseSupport.Outcome outcome = codeParse.extract(
                codeInput(run, text, hints), run.minConfidence, false);
        if (outcome.accepted().isPresent()) {
            CodeExtraction extraction = outcome.accepted().get();
            log.info("Code extraction stood in for the AI ({}): {} at {}", reason,
                    extraction.strategy(), String.format(Locale.ROOT, "%.2f", extraction.confidence()));
            return codeResult(extraction, reason);
        }
        String ai = aiFailure == null
                ? "AI extraction was skipped (" + reason.description() + ")"
                : "AI extraction failed (" + reason.description() + providerDetail(aiFailure) + ")";
        throw new IllegalStateException(ai + ", and code-based extraction could not read a table "
                + "from '" + run.context.fileName() + "' with enough confidence ("
                + outcome.describeBest() + ", needs " + String.format(Locale.ROOT, "%.2f",
                run.minConfidence) + ").", aiFailure);
    }

    private static ParseResult codeResult(CodeExtraction extraction, FallbackReason reason) {
        return extraction.toParseResult().withProvenance(ParseProvenance.code(
                extraction.strategy().name(), extraction.confidence(), reason));
    }

    /**
     * The provider's own explanation (bad key, no credits, context too long),
     * when the failure carries one. Reply-parsing failures add nothing: their
     * messages can quote the model's output, which must not reach the job.
     */
    private static String providerDetail(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof NonTransientAiException || current instanceof AiNotConfiguredException) {
                return ": " + shorten(AiErrorMessages.friendlyMessage(current));
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "";
    }

    private static String shorten(String message) {
        String value = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    // ---- LLM extraction ----------------------------------------------------------

    private ParseResult extractWithLlm(Run run, PiiTokenizer.Session session, String text,
                                       TableExtractionHints hints, TableExtractionPlan plan) {
        PromptStrategy strategy = promptFactory.getPrompt("TableExtraction");
        List<TemplateFields> templateFields = run.context.templateFields();
        boolean combined = run.parsing.isCombinedExtractionMapping()
                && useCombinedExtraction(templateFields);
        run.headerText = plan.headerText() == null ? "" : plan.headerText();

        ParseResult extracted = plan.singlePass()
                ? extractSinglePass(run, strategy, session, plan.fullDocumentText(), hints,
                templateFields, combined)
                : extractBatched(run, strategy, session, plan, hints, templateFields, combined);
        ParseProvenance provenance = run.provenance(plan.singlePass());

        if (extracted.table() == null || extracted.table().getRows() == null) {
            return extracted.withProvenance(provenance);
        }
        TableData table = TableColumnAligner.alignToHints(extracted.table(), hints);
        Map<String, String> mapping = extracted.suggestedMapping();
        if (table.getColumns() != null && extracted.table().getColumns() != null
                && !table.getColumns().equals(extracted.table().getColumns())) {
            mapping = TableColumnAligner.alignMappingKeys(
                    mapping, extracted.table().getColumns(), table.getColumns());
        }
        return new ParseResult(
                new TableData(table.getColumns(),
                        session.detokenizeRows(table.getRows())),
                mapping == null ? Map.of() : mapping,
                provenance);
    }

    private static boolean useCombinedExtraction(List<TemplateFields> templateFields) {
        return templateFields != null && !templateFields.isEmpty();
    }

    private ParseResult extractSinglePass(Run run, PromptStrategy strategy, PiiTokenizer.Session session,
                                          String text, TableExtractionHints hints,
                                          List<TemplateFields> templateFields, boolean combined) {
        String tokenizedText = session.tokenizeText(text);
        if (combined && run.parsing.isCombinedExtractionMapping()) {
            String prompt = strategy.buildPrompt(new TableExtractionPromptInput(
                    tokenizedText, hints, TableExtractionMode.EXTRACT_AND_MAP,
                    null, null, templateFields));
            TableExtractAndMapResult result = extractExtractAndMapWithRetry(
                    run, prompt, hints, templateFieldNames(templateFields));
            return toParseResult(result);
        }
        String prompt = strategy.buildPrompt(
                new TableExtractionPromptInput(tokenizedText, hints, TableExtractionMode.FULL, null, null));
        return new ParseResult(extractWithRetry(run, prompt, hints), Map.of());
    }

    private ParseResult extractBatched(Run run, PromptStrategy strategy, PiiTokenizer.Session session,
                                       TableExtractionPlan plan, TableExtractionHints hints,
                                       List<TemplateFields> templateFields, boolean combined) {
        if (combined && run.parsing.isCombinedExtractionMapping()) {
            return extractCombinedBatched(run, strategy, session, plan, hints, templateFields);
        }

        List<String> columns = resolveColumnsViaLlm(run, strategy, session, plan, hints);

        log.info("Batched LLM extraction: {} columns, {} batches (~{} rows), concurrency {}",
                columns.size(), plan.batches().size(), plan.estimatedDataRows(),
                run.parsing.getLlmBatchConcurrency());

        List<Map<String, String>> allRows = extractBatchesParallel(
                run, strategy, session, plan.batches(), columns, hints);

        if (hints != null && hints.dataRowCount() > 0
                && allRows.size() != hints.dataRowCount()) {
            log.warn("Batched extraction returned {} rows but hints expect {}",
                    allRows.size(), hints.dataRowCount());
        }
        return new ParseResult(new TableData(columns, allRows), Map.of());
    }

    private ParseResult extractCombinedBatched(Run run, PromptStrategy strategy,
                                               PiiTokenizer.Session session,
                                               TableExtractionPlan plan, TableExtractionHints hints,
                                               List<TemplateFields> templateFields) {
        List<TableRowBatch> batches = plan.batches();
        if (batches.isEmpty()) {
            return new ParseResult(new TableData(List.of(), List.of()), Map.of());
        }

        TableRowBatch firstBatch = batches.get(0);
        TableExtractAndMapResult firstResult = extractExtractAndMapBatchWithRetry(
                run, strategy, session, firstBatch, hints, templateFields);
        List<String> columns = firstResult.getColumns();
        Map<String, String> suggestedMapping = firstResult.getMapping() == null
                ? Map.of() : new LinkedHashMap<>(firstResult.getMapping());

        log.info("Combined batched extraction: {} columns, {} batches (~{} rows), concurrency {}",
                columns.size(), batches.size(), plan.estimatedDataRows(),
                run.parsing.getLlmBatchConcurrency());

        List<Map<String, String>> allRows = new ArrayList<>(
                firstResult.getRows() == null ? List.of() : firstResult.getRows());
        if (batches.size() > 1) {
            allRows.addAll(extractBatchesParallel(
                    run, strategy, session, batches.subList(1, batches.size()), columns, hints));
        }

        if (hints != null && hints.dataRowCount() > 0
                && allRows.size() != hints.dataRowCount()) {
            log.warn("Combined batched extraction returned {} rows but hints expect {}",
                    allRows.size(), hints.dataRowCount());
        }
        return new ParseResult(new TableData(columns, allRows), suggestedMapping);
    }

    private List<Map<String, String>> extractBatchesParallel(Run run, PromptStrategy strategy,
                                                              PiiTokenizer.Session session,
                                                              List<TableRowBatch> batches,
                                                              List<String> columns,
                                                              TableExtractionHints hints) {
        if (batches.isEmpty()) {
            return List.of();
        }
        int concurrency = Math.max(1, run.parsing.getLlmBatchConcurrency());
        if (concurrency == 1 || batches.size() == 1) {
            List<Map<String, String>> allRows = new ArrayList<>();
            for (TableRowBatch batch : batches) {
                allRows.addAll(batchRows(run, strategy, null, session, batch, columns, hints));
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
                            List<Map<String, String>> batchRows = batchRows(
                                    run, strategy, tokenizedBatch, null, batch, columns, hints);
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

    /**
     * One batch's rows: from the LLM, or — in AI_WITH_CODE_FALLBACK mode — by
     * code when the LLM fails on it or the AI time budget is spent.
     */
    private List<Map<String, String>> batchRows(Run run, PromptStrategy strategy, String tokenizedBatch,
                                                PiiTokenizer.Session session, TableRowBatch batch,
                                                List<String> columns, TableExtractionHints hints) {
        if (run.fallback && run.budgetSpent()) {
            return repairBatch(run, batch, columns, FallbackReason.TIMEOUT,
                    new LlmExtractionFailedException("The AI time budget of " + run.budget
                            + " was spent before batch " + batch.batchIndex() + " of "
                            + batch.totalBatches() + ".",
                            new TimeoutException("AI extraction time budget spent")));
        }
        try {
            String tokenized = tokenizedBatch != null ? tokenizedBatch : session.tokenizeText(batch.rowText());
            return extractRowBatchWithRetry(run, strategy, tokenized, batch, columns, hints);
        } catch (RuntimeException failure) {
            if (!run.fallback) {
                throw failure;
            }
            return repairBatch(run, batch, columns, AiFailureClassifier.classify(failure), failure);
        }
    }

    /**
     * Re-parses one batch by code with the known columns (AI_PARTIAL). When
     * code cannot read it either, {@code failure} propagates and the whole
     * document falls back to code.
     */
    private List<Map<String, String>> repairBatch(Run run, TableRowBatch batch, List<String> columns,
                                                  FallbackReason reason, RuntimeException failure) {
        String text = batch.batchIndex() == 1 || run.headerText.isBlank()
                ? batch.rowText() : run.headerText.trim() + "\n" + batch.rowText();
        int expectedRows = batch.endRow() - batch.startRow() + 1;
        ExtractionInput input = ExtractionInput.builder()
                .text(text)
                .fileType(run.extension)
                .fields(columns.stream().map(c -> FieldSpec.of(c, FieldSpec.Type.TEXT)).toList())
                .hints(new TableExtractionHints(run.extension, "batch", columns.size(),
                        columns, expectedRows))
                .build();
        Optional<CodeExtraction> repaired =
                codeParse.extract(input, run.minConfidence, false).accepted();
        if (repaired.isEmpty()) {
            throw failure;
        }
        CodeExtraction extraction = repaired.get();
        run.repairs.add(new Repair(batch.batchIndex(), extraction.strategy().name(),
                extraction.confidence(), reason));
        log.info("Batch {} of {} re-parsed by code after the AI failed ({}): {} at {}",
                batch.batchIndex(), batch.totalBatches(), reason, extraction.strategy(),
                String.format(Locale.ROOT, "%.2f", extraction.confidence()));
        return alignToColumns(extraction, columns);
    }

    /** Code rows keyed by the batch's known columns (via the extractor's column→field mapping). */
    private static List<Map<String, String>> alignToColumns(CodeExtraction extraction, List<String> columns) {
        Map<String, String> sourceByColumn = new LinkedHashMap<>();
        extraction.suggestedMapping().forEach((source, target) -> {
            if (target != null) {
                sourceByColumn.putIfAbsent(target, source);
            }
        });
        List<Map<String, String>> rows = new ArrayList<>(extraction.rows().size());
        for (Map<String, String> row : extraction.rows()) {
            Map<String, String> aligned = new LinkedHashMap<>();
            for (String column : columns) {
                String source = sourceByColumn.getOrDefault(column, column);
                aligned.put(column, row.getOrDefault(source, ""));
            }
            rows.add(aligned);
        }
        return rows;
    }

    private List<String> resolveColumnsViaLlm(Run run, PromptStrategy strategy, PiiTokenizer.Session session,
                                              TableExtractionPlan plan, TableExtractionHints hints) {
        if (hints != null && hints.candidateHeaderCells() != null
                && !hints.candidateHeaderCells().isEmpty()
                && hints.expectedColumnCount() == hints.candidateHeaderCells().size()) {
            return hints.candidateHeaderCells();
        }
        String schemaText = buildSchemaText(plan);
        String tokenizedSchema = session.tokenizeText(schemaText);
        String schemaPrompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedSchema, hints, TableExtractionMode.SCHEMA, null, null));
        return extractSchemaWithRetry(run, schemaPrompt, hints);
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

    private List<String> extractSchemaWithRetry(Run run, String prompt, TableExtractionHints hints) {
        TableSchemaResult schema;
        try {
            schema = callLlmForSchema(prompt);
        } catch (RuntimeException e) {
            throw llmParseFailure("schema extraction", e);
        }
        if (schema == null || schema.getColumns() == null || schema.getColumns().isEmpty()) {
            throw new LlmExtractionFailedException("LLM schema extraction returned no columns.");
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
            Run run, PromptStrategy strategy, PiiTokenizer.Session session, TableRowBatch batch,
            TableExtractionHints hints, List<TemplateFields> templateFields) {
        String tokenizedBatch = session.tokenizeText(batch.rowText());
        String prompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedBatch, hints, TableExtractionMode.EXTRACT_AND_MAP,
                null, batch, templateFields));
        return extractExtractAndMapWithRetry(run, prompt, hints, templateFieldNames(templateFields));
    }

    private TableExtractAndMapResult extractExtractAndMapWithRetry(
            Run run, String prompt, TableExtractionHints hints, List<String> templateFieldNames) {
        TableExtractAndMapResult extracted;
        try {
            extracted = callLlmForExtractAndMap(prompt, templateFieldNames);
        } catch (RuntimeException first) {
            if (!run.correctiveRetryWorthIt(first)) {
                throw llmParseFailure("combined extraction", first);
            }
            log.warn("Combined extraction JSON parse failed; retrying once: {}", first.getMessage());
            String retryPrompt = prompt + TableExtractionPrompt.jsonParseCorrectiveSuffix(first.getMessage());
            try {
                extracted = callLlmForExtractAndMap(retryPrompt, templateFieldNames);
            } catch (RuntimeException second) {
                throw llmParseFailure("combined extraction", second);
            }
        }
        if (extracted == null || extracted.getColumns() == null || extracted.getColumns().isEmpty()) {
            throw new LlmExtractionFailedException("LLM combined extraction returned no columns.");
        }
        if (extracted.getRows() == null || extracted.getRows().isEmpty()) {
            throw new LlmExtractionFailedException("LLM combined extraction returned no rows.");
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

    private List<Map<String, String>> extractRowBatchWithRetry(Run run, PromptStrategy strategy,
                                                                String tokenizedBatch,
                                                                TableRowBatch batch,
                                                                List<String> columns,
                                                                TableExtractionHints hints) {
        String batchPrompt = strategy.buildPrompt(new TableExtractionPromptInput(
                tokenizedBatch, hints, TableExtractionMode.ROW_BATCH, columns, batch));
        try {
            TableRowsResult result = callLlmForRows(batchPrompt, columns);
            if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
                return result.getRows();
            }
        } catch (RuntimeException first) {
            String phase = "batch " + batch.batchIndex() + " of " + batch.totalBatches()
                    + " (rows " + batch.startRow() + "–" + batch.endRow() + ")";
            if (!run.correctiveRetryWorthIt(first)) {
                throw llmParseFailure(phase, first);
            }
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
                throw llmParseFailure(phase, second);
            }
        }
        throw new LlmExtractionFailedException(
                "LLM table extraction returned no rows for batch " + batch.batchIndex()
                        + " of " + batch.totalBatches()
                        + " (rows " + batch.startRow() + "–" + batch.endRow() + ").");
    }

    private TableData extractWithRetry(Run run, String prompt, TableExtractionHints hints) {
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

    private static LlmExtractionFailedException llmParseFailure(String phase, RuntimeException cause) {
        return new LlmExtractionFailedException(
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

    // ---- per-document state --------------------------------------------------------

    /** One batch the code extractor re-parsed after the AI failed on it. */
    private record Repair(int batchIndex, String strategy, double confidence, FallbackReason reason) {
    }

    /** One document's parse: mode, settings and — for AI_PARTIAL — the repaired batches. */
    private final class Run {

        final ParseContext context;
        final Path file;
        final String extension;
        final Integer bankId;
        final ExtractionMode mode;
        final boolean fallback;
        final double minConfidence;
        final Duration budget;
        final long startedNanos = System.nanoTime();
        final Queue<Repair> repairs = new ConcurrentLinkedQueue<>();
        /** The bank's parsing settings (ws20), resolved once for the document. */
        final ParsingProperties parsing;
        final ExcelParsingProperties excel;
        volatile String headerText = "";

        Run(ParseContext context, Path file, String extension, Integer bankId, ExtractionMode mode) {
            this.context = context;
            if (parsingSettings == null) {
                this.parsing = parsingProperties;
                this.excel = excelParsing;
            } else if (bankId == null) {
                this.parsing = parsingSettings.forCurrentBank();
                this.excel = excelParsing;
            } else {
                this.parsing = parsingSettings.forBank(bankId);
                this.excel = parsingSettings.excelForBank(bankId);
            }
            this.file = file;
            this.extension = extension;
            this.bankId = bankId;
            this.mode = mode;
            this.fallback = mode == ExtractionMode.AI_WITH_CODE_FALLBACK;
            this.minConfidence = mode == ExtractionMode.AI_ONLY
                    ? CodeTableExtractor.DEFAULT_MIN_CONFIDENCE : modes.minConfidence(bankId);
            Duration configured = aiClient == null ? null : aiClient.getExtraction().getTimeBudget();
            this.budget = configured == null || configured.isNegative()
                    ? Duration.ofMinutes(10) : configured;
        }

        boolean budgetSpent() {
            return System.nanoTime() - startedNanos >= budget.toNanos();
        }

        /**
         * AI_ONLY keeps the historic one corrective re-prompt for every
         * failure; with a code fallback available it is spent only on a reply
         * that came back unusable — re-prompting a timeout or a 500 would just
         * delay the fallback.
         */
        boolean correctiveRetryWorthIt(RuntimeException failure) {
            return !fallback || AiFailureClassifier.isReplyProblem(failure);
        }

        ParseProvenance provenance(boolean singlePass) {
            if (repairs.isEmpty()) {
                return ParseProvenance.ai(singlePass
                        ? ParseProvenance.LLM_SINGLE_PASS : ParseProvenance.LLM_BATCHED);
            }
            List<Repair> ordered = repairs.stream()
                    .sorted(Comparator.comparingInt(Repair::batchIndex))
                    .toList();
            double lowest = ordered.stream().mapToDouble(Repair::confidence).min().orElse(0);
            Repair first = ordered.get(0);
            return new ParseProvenance(ParsedBy.AI_PARTIAL, first.strategy(), lowest, first.reason());
        }
    }
}
