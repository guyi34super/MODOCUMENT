package com.modocument.infrastructure.transform;

import com.modocument.application.dto.DocumentExtractionResult;
import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseProvenance;
import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import com.modocument.application.dto.TableRowBatch;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ai.AiCircuitBreaker;
import com.modocument.infrastructure.ai.AiClientProperties;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.ai.AiPromptRefusedException;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.StructuredTableMaterializer;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.TableExtractionBatchPlanner;
import com.modocument.infrastructure.file.TableExtractionHintsBuilder;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.file.ParsingSettings;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import com.modocument.infrastructure.transform.code.ExtractionCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IngestionParseStepImplTest {

    private final UploadStorage uploadStorage = mock(UploadStorage.class);
    private final TabularNormalizer tabularNormalizer = mock(TabularNormalizer.class);
    private final DocumentParser documentParser = mock(DocumentParser.class);
    private final PromptFactory promptFactory = mock(PromptFactory.class);
    private final AiFilesService aiFilesService = mock(AiFilesService.class);
    private final PiiTokenizer tokenizer = new PiiTokenizer(new PrivacyProperties());
    private final ExcelParsingProperties excelParsing = new ExcelParsingProperties();
    private final ParsingProperties parsingProperties = new ParsingProperties();
    private final TableExtractionHintsBuilder hintsBuilder = mock(TableExtractionHintsBuilder.class);
    private final TableExtractionBatchPlanner batchPlanner = mock(TableExtractionBatchPlanner.class);
    private final StructuredTableMaterializer structuredMaterializer =
            mock(StructuredTableMaterializer.class);
    private final SettingsReader settings = mock(SettingsReader.class);
    private final LlmRateLimiter quota = mock(LlmRateLimiter.class);
    private final AiClientProperties aiClient = new AiClientProperties();
    private AiCircuitBreaker breaker = AiCircuitBreaker.disabled();

    private IngestionParseStepImpl step;

    @TempDir
    Path tempDir;

    private static final TableData LLM_TABLE = new TableData(
            List.of("Name", "City"),
            List.of(Map.of("Name", "Ada", "City", "London")));

    private static final String ROW_LINES = "Row 1: Name\tCity\nRow 2: Ada\tLondon";

    private static final ParseContext CONTEXT = new ParseContext(
            "book.xlsx", 1, 1, List.of(templateField("FirstName")));

    /** A context whose template fields match the row-line document, so code can read it. */
    private static final ParseContext NOTES = new ParseContext(
            "notes.txt", 1, 1, List.of(templateField("Name"), templateField("City")));

    private static TemplateFields templateField(String name) {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        field.setTemplateDataTypeId(1);
        field.setTemplateDataType(new TemplateDataType(1, "Text"));
        return field;
    }

    private static TemplateFields typedField(String name, int typeId, String type) {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        field.setTemplateDataTypeId(typeId);
        field.setTemplateDataType(new TemplateDataType(typeId, type));
        return field;
    }

    @BeforeEach
    void setUp() throws IOException {
        rebuildStep();

        for (String name : List.of("book.xlsx", "book.xlsm", "people.csv", "notes.txt", "report.pdf")) {
            Path file = tempDir.resolve(name);
            Files.writeString(file, "x");
            when(uploadStorage.resolve(name)).thenReturn(file);
        }

        PromptStrategy strategy = mock(PromptStrategy.class);
        when(strategy.buildPrompt(any())).thenReturn("prompt");
        when(promptFactory.getPrompt("TableExtraction")).thenReturn(strategy);
        when(documentParser.extract(any())).thenReturn(
                new DocumentExtractionResult(ROW_LINES, "local"));
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".xlsx", "local", 2, List.of("Name", "City"), 1));
        when(batchPlanner.plan(anyString(), any(), any()))
                .thenReturn(TableExtractionPlan.singlePass(ROW_LINES, 1, 2));
        when(structuredMaterializer.tryMaterialize(any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        doReturn(LLM_TABLE).when(aiFilesService).sendForObject(any(PromptEnvelope.class), any());

        // Shipped settings: AI on, default mode AI_WITH_CODE_FALLBACK, 0.75.
        when(settings.getEnumForPlatform(SettingKeys.EXTRACTION_DEFAULT_MODE))
                .thenReturn("AI_WITH_CODE_FALLBACK");
        when(settings.getEnum(eq(SettingKeys.EXTRACTION_DEFAULT_MODE), anyInt()))
                .thenReturn("AI_WITH_CODE_FALLBACK");
        when(settings.getDecimalForPlatform(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE))
                .thenReturn(new BigDecimal("0.75"));
        when(settings.getBoolForPlatform(SettingKeys.AI_ENABLED)).thenReturn(true);
        when(aiFilesService.isConfigured()).thenReturn(true);
        when(quota.tryCheck(anyInt())).thenReturn(true);
        when(quota.tryCheck(anyInt(), anyInt())).thenReturn(true);
    }

    private void rebuildStep() {
        rebuildStep(null);
    }

    private void rebuildStep(ParsingSettings parsingSettings) {
        CodeParseSupport codeParse = new CodeParseSupport(
                ExtractionCorpus.extractor(), new MappingProperties(), null);
        step = new IngestionParseStepImpl(uploadStorage, tabularNormalizer, documentParser,
                promptFactory, aiFilesService, tokenizer, excelParsing, parsingProperties,
                hintsBuilder, batchPlanner, structuredMaterializer, codeParse,
                new ExtractionModeResolver(settings),
                new AiPreflight(settings, aiFilesService, breaker, quota), aiClient,
                parsingSettings);
    }

    // ---- the AI path (unchanged behaviour) ---------------------------------------

    @Test
    void excelGoesThroughTheLlmByDefault() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class))).thenReturn("""
                {"columns":["Name","City"],"mapping":{"Name":"FirstName","City":null},
                "rows":[{"Name":"Ada","City":"London"}]}
                """);

        ParseResult result = step.parse(CONTEXT);

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        assertThat(result.suggestedMapping()).containsEntry("Name", "FirstName");
        assertThat(result.provenance()).isEqualTo(ParseProvenance.ai(ParseProvenance.LLM_SINGLE_PASS));
        verify(documentParser).extract(any());
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void usesDeterministicFastPathWhenAvailable() {
        parsingProperties.setDeterministicEnabled(true);
        TableData deterministic = new TableData(
                List.of("Name", "City"),
                List.of(Map.of("Name", "Ada", "City", "London")));
        when(structuredMaterializer.tryMaterialize(any(), anyString(), any(), any()))
                .thenReturn(Optional.of(deterministic));

        ParseResult result = step.parse(CONTEXT);

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        assertThat(result.suggestedMapping()).isEmpty();
        // Code produced it, but nothing fell back: no reason, no fallback audit.
        assertThat(result.provenance().parsedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(result.provenance().strategy()).isEqualTo(ParseProvenance.STRUCTURED);
        assertThat(result.provenance().isFallback()).isFalse();
        verifyNoInteractions(aiFilesService);
    }

    /** ws20: the bank's parsing settings, resolved per job, can turn the fast path ON too. */
    @Test
    void theBanksParsingSettingsDecideTheDeterministicFastPath() {
        parsingProperties.setDeterministicEnabled(false);
        for (int bank : new int[] {7, 8}) {
            when(settings.getBool(SettingKeys.PARSING_DETERMINISTIC_ENABLED, bank)).thenReturn(bank == 7);
            when(settings.getBool(SettingKeys.PARSING_COMBINED_EXTRACTION_MAPPING, bank)).thenReturn(true);
            when(settings.getBool(SettingKeys.PARSING_EXCEL_LLM_ENABLED, bank)).thenReturn(true);
            when(settings.getInt(SettingKeys.PARSING_LLM_BATCH_SIZE, bank)).thenReturn(50);
            when(settings.getInt(SettingKeys.PARSING_LLM_BATCH_CONCURRENCY, bank)).thenReturn(8);
            when(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, bank)).thenReturn("AI_ONLY");
        }
        rebuildStep(new ParsingSettings(parsingProperties, settings));
        when(structuredMaterializer.tryMaterialize(any(), anyString(), any(), any()))
                .thenReturn(Optional.of(LLM_TABLE));

        ParseResult bankOn = step.parse(new ParseContext("notes.txt", 1, 1, List.of(), 7, null));
        assertThat(bankOn.provenance().strategy()).isEqualTo(ParseProvenance.STRUCTURED);
        verifyNoInteractions(aiFilesService);

        ParseResult bankOff = step.parse(new ParseContext("notes.txt", 1, 1, List.of(), 8, null));
        assertThat(bankOff.provenance().parsedBy()).isEqualTo(ParsedBy.AI);
    }

    @Test
    void csvGoesThroughLocalTextAndLlm() {
        when(documentParser.extract(any())).thenReturn(
                new DocumentExtractionResult("Name,City\nAda,London", "local"));
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".csv", "local", 2, List.of("Name", "City"), 1));
        when(batchPlanner.plan(anyString(), any(), any()))
                .thenReturn(TableExtractionPlan.singlePass("Name,City\nAda,London", 1, 2));
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class))).thenReturn("""
                {"columns":["Name","City"],"mapping":{"Name":"FirstName","City":null},
                "rows":[{"Name":"Ada","City":"London"}]}
                """);

        ParseResult result = step.parse(new ParseContext("people.csv", 1, 1, List.of(templateField("FirstName"))));

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        verify(documentParser).extract(any());
        verify(aiFilesService).sendPrompt(any(PromptEnvelope.class));
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void excelFallsBackToDeterministicWhenLlmDisabled() {
        excelParsing.setLlmEnabled(false);
        TableData deterministic = new TableData(List.of("A"), List.of(Map.of("A", "1")));
        when(tabularNormalizer.normalize(any())).thenReturn(deterministic);

        assertThat(step.parse(CONTEXT).table()).isSameAs(deterministic);
        verify(tabularNormalizer).normalize(any());
        verifyNoInteractions(documentParser, aiFilesService);
    }

    /** AC-EXT-05: AI_ONLY keeps the pre-phase-2 behaviour — no code fallback. */
    @Test
    void llmJsonFailureThrowsInsteadOfPoiFallback() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new RuntimeException(
                        "com.fasterxml.jackson.databind.JsonMappingException: Unexpected end-of-input"));

        assertThatThrownBy(() -> step.parse(CONTEXT.withExtractionMode(ExtractionMode.AI_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM table extraction failed");
        verify(tabularNormalizer, never()).normalize(any());
        // The historic corrective re-prompt still happens in AI_ONLY.
        verify(aiFilesService, times(2)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aiOnlyFailsOnATransportErrorTooAndSkipsThePreflight() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new TransientAiException("HTTP 500 - upstream failed"));
        when(settings.getBoolForPlatform(SettingKeys.AI_ENABLED)).thenReturn(false);

        assertThatThrownBy(() -> step.parse(NOTES.withExtractionMode(ExtractionMode.AI_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM table extraction failed");
        verifyNoInteractions(quota);
    }

    @Test
    void combinedBatchedUsesExtractAndMapForFirstBatchOnly() {
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".xlsx", "local", 2,
                        List.of("Emp ID", "Name"), 2));
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(twoMarkdownBatches());
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("""
                        {"columns":["Emp ID","Name"],"mapping":{"Emp ID":"FirstName","Name":null},
                        "rows":[{"Emp ID":"E001","Name":"Ada"}]}""")
                .thenReturn("{\"rows\":[{\"Emp ID\":\"E002\",\"Name\":\"Grace\"}]}");

        ParseResult result = step.parse(CONTEXT);

        assertThat(result.table().getRows()).hasSize(2);
        assertThat(result.suggestedMapping()).containsEntry("Emp ID", "FirstName");
        assertThat(result.provenance()).isEqualTo(ParseProvenance.ai(ParseProvenance.LLM_BATCHED));
        verify(aiFilesService, times(2)).sendPrompt(any(PromptEnvelope.class));
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void legacyBatchedUsesSchemaWhenCombinedDisabled() {
        parsingProperties.setCombinedExtractionMapping(false);
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(new TableExtractionPlan(
                false,
                "Row 1: Name\tCity",
                List.of("Row 2: Ada\tLondon"),
                List.of(new TableRowBatch(1, 1, 2, 3, "Row 2: Ada\tLondon")),
                1,
                2,
                "full text"));
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".txt", "local", 2, List.of(), 1));
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("[\"Name\",\"City\"]")
                .thenReturn("[{\"Name\":\"Ada\",\"City\":\"London\"}]");

        ParseResult result = step.parse(new ParseContext("notes.txt", 1, 1, List.of(templateField("FirstName"))));

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        assertThat(result.suggestedMapping()).isEmpty();
        verify(aiFilesService, times(2)).sendPrompt(any(PromptEnvelope.class));
    }

    // ---- AI_WITH_CODE_FALLBACK (the default) -----------------------------------

    @Test
    void defaultModeFallsBackToCodeWhenTheReplyIsUnusable() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new RuntimeException(
                        "com.fasterxml.jackson.databind.JsonMappingException: Unexpected end-of-input"));

        ParseResult result = step.parse(NOTES);

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        assertThat(result.table().getRows()).containsExactly(Map.of("Name", "Ada", "City", "London"));
        ParseProvenance provenance = result.provenance();
        assertThat(provenance.parsedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(provenance.fallbackReason()).isEqualTo(FallbackReason.INVALID_OUTPUT);
        assertThat(provenance.strategy()).isEqualTo("ROW_LINE");
        assertThat(provenance.confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(provenance.isFallback()).isTrue();
        assertThat(result.suggestedMapping()).containsEntry("Name", "Name");
        // An unusable reply is worth the one corrective re-prompt.
        verify(aiFilesService, times(2)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aServerErrorFallsBackAtOnceWithoutTheCorrectiveRePrompt() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new TransientAiException("HTTP 500 - upstream failed"));

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().parsedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.ERROR);
        verify(aiFilesService, times(1)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aTimeoutFallsBackWithReasonTimeout() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new ResourceAccessException("I/O error: request timed out",
                        new SocketTimeoutException("timed out")));

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.TIMEOUT);
        verify(aiFilesService, times(1)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aPromptRefusalFallsBackPrivacySafe() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new AiPromptRefusedException("AI prompt refused: raw identifiers"));

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.PROMPT_REFUSED);
        assertThat(result.table().getRows()).hasSize(1);
    }

    @Test
    void aiSwitchedOffForTheBankSkipsTheAi() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 7)).thenReturn(false);
        when(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, 7)).thenReturn("AI_WITH_CODE_FALLBACK");
        when(settings.getDecimal(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, 7))
                .thenReturn(new BigDecimal("0.75"));

        ParseResult result = step.parse(new ParseContext("notes.txt", 1, 1,
                NOTES.templateFields(), 7, null));

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.AI_DISABLED);
        verify(aiFilesService, never()).sendPrompt(any());
        verifyNoInteractions(promptFactory, quota);
    }

    @Test
    void noApiKeySkipsTheAi() {
        when(aiFilesService.isConfigured()).thenReturn(false);

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.NOT_CONFIGURED);
        verify(aiFilesService, never()).sendPrompt(any());
    }

    @Test
    void aSpentQuotaSkipsTheAi() {
        when(quota.tryCheck(anyInt())).thenReturn(false);

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.QUOTA);
        verify(aiFilesService, never()).sendPrompt(any());
    }

    @Test
    void anOpenCircuitSkipsTheAi() {
        breaker = new AiCircuitBreaker(() -> 1, () -> Duration.ofMinutes(5), Clock.systemUTC(),
                opened -> {
                });
        breaker.tryAcquire();
        breaker.onFailure(new ResourceAccessException("connection refused"));
        rebuildStep();

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.CIRCUIT_OPEN);
        verify(aiFilesService, never()).sendPrompt(any());
        verifyNoInteractions(quota);
    }

    @Test
    void whenCodeFailsTooTheJobFailsNamingBothFailures() {
        when(documentParser.extract(any())).thenReturn(new DocumentExtractionResult(
                "Dear customer, thank you for your letter of last week.", "local"));
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new TransientAiException("HTTP 500 - upstream failed"));

        assertThatThrownBy(() -> step.parse(NOTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI extraction failed (AI error")
                .hasMessageContaining("code-based extraction could not read a table")
                .hasMessageContaining("needs 0.75");
    }

    @Test
    void aiPartialRepairsAFailedBatchByCode() {
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".txt", "local", 2,
                        List.of("Emp ID", "Name"), 2));
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(twoMarkdownBatches());
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("""
                        {"columns":["Emp ID","Name"],"mapping":{"Emp ID":"FirstName","Name":null},
                        "rows":[{"Emp ID":"E001","Name":"Ada"}]}""")
                .thenThrow(new TransientAiException("HTTP 503 - overloaded"));

        ParseResult result = step.parse(new ParseContext("notes.txt", 1, 1,
                List.of(templateField("FirstName"))));

        assertThat(result.table().getColumns()).containsExactly("Emp ID", "Name");
        assertThat(result.table().getRows()).containsExactly(
                Map.of("Emp ID", "E001", "Name", "Ada"),
                Map.of("Emp ID", "E002", "Name", "Grace"));
        assertThat(result.suggestedMapping()).containsEntry("Emp ID", "FirstName");
        ParseProvenance provenance = result.provenance();
        assertThat(provenance.parsedBy()).isEqualTo(ParsedBy.AI_PARTIAL);
        assertThat(provenance.fallbackReason()).isEqualTo(FallbackReason.ERROR);
        assertThat(provenance.strategy()).isEqualTo("MARKDOWN_TABLE");
        assertThat(provenance.isFallback()).isTrue();
        verify(aiFilesService, times(2)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aSpentTimeBudgetSendsTheRemainingBatchesToCode() {
        aiClient.getExtraction().setTimeBudget(Duration.ZERO);
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".txt", "local", 2,
                        List.of("Emp ID", "Name"), 2));
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(twoMarkdownBatches());
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class))).thenReturn("""
                {"columns":["Emp ID","Name"],"mapping":{"Emp ID":"FirstName","Name":null},
                "rows":[{"Emp ID":"E001","Name":"Ada"}]}""");

        ParseResult result = step.parse(new ParseContext("notes.txt", 1, 1,
                List.of(templateField("FirstName"))));

        assertThat(result.table().getRows()).hasSize(2);
        assertThat(result.provenance().parsedBy()).isEqualTo(ParsedBy.AI_PARTIAL);
        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.TIMEOUT);
        // Only the first (mapping) batch went to the AI.
        verify(aiFilesService, times(1)).sendPrompt(any(PromptEnvelope.class));
    }

    @Test
    void aiOnlyNeverRepairsAFailedBatch() {
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".txt", "local", 2,
                        List.of("Emp ID", "Name"), 2));
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(twoMarkdownBatches());
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("""
                        {"columns":["Emp ID","Name"],"mapping":{"Emp ID":"FirstName","Name":null},
                        "rows":[{"Emp ID":"E001","Name":"Ada"}]}""")
                .thenThrow(new TransientAiException("HTTP 503 - overloaded"));

        assertThatThrownBy(() -> step.parse(new ParseContext("notes.txt", 1, 1,
                List.of(templateField("FirstName")), null, ExtractionMode.AI_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM table extraction failed during batch 2");
    }

    // ---- CODE_ONLY ---------------------------------------------------------------

    @Test
    void codeOnlyNeverTouchesTheAi() {
        ParseResult result = step.parse(NOTES.withExtractionMode(ExtractionMode.CODE_ONLY));

        assertThat(result.table().getRows()).containsExactly(Map.of("Name", "Ada", "City", "London"));
        assertThat(result.provenance().parsedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.MODE_CODE_ONLY);
        assertThat(result.provenance().isFallback()).isFalse();
        verifyNoInteractions(aiFilesService, promptFactory, quota);
    }

    @Test
    void theBankDefaultModeAppliesWhenTheTemplateHasNone() {
        when(settings.getEnumForPlatform(SettingKeys.EXTRACTION_DEFAULT_MODE)).thenReturn("CODE_ONLY");

        ParseResult result = step.parse(NOTES);

        assertThat(result.provenance().fallbackReason()).isEqualTo(FallbackReason.MODE_CODE_ONLY);
        verifyNoInteractions(aiFilesService);
    }

    @Test
    void codeOnlyReadsExcelWithTheSheetReaderEvenWhenExcelGoesToTheAi() {
        excelParsing.setLlmEnabled(true);
        TableData sheet = new TableData(List.of("A"), List.of(Map.of("A", "1")));
        when(tabularNormalizer.normalize(any())).thenReturn(sheet);

        ParseResult result = step.parse(CONTEXT.withExtractionMode(ExtractionMode.CODE_ONLY));

        assertThat(result.table()).isSameAs(sheet);
        assertThat(result.provenance().strategy()).isEqualTo(ParseProvenance.NATIVE_SHEET);
        verifyNoInteractions(aiFilesService, documentParser);
    }

    @Test
    void codeOnlyFailsWithAReasonWhenNoTableIsFound() {
        when(documentParser.extract(any())).thenReturn(new DocumentExtractionResult(
                "Dear customer, thank you for your letter of last week.", "local"));

        assertThatThrownBy(() -> step.parse(NOTES.withExtractionMode(ExtractionMode.CODE_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Code-based extraction could not find a table")
                .hasMessageContaining("0.40");
        verifyNoInteractions(aiFilesService);
    }

    // ---- windows-1252 CSV (FileTextReader and the pre-scan throw) --------------------

    @Test
    void aCp1252CsvIsReadByCodeWhenTheAiFails() throws IOException {
        ParseContext context = cp1252Context();
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new TransientAiException("HTTP 500 - upstream failed"));

        ParseResult result = step.parse(context);

        assertThat(result.provenance().parsedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(result.provenance().strategy()).isEqualTo("DELIMITED");
        assertThat(result.table().getRows()).hasSize(6);
        assertThat(result.table().getRows().get(0)).containsValue("Zoë Lefèvre");
    }

    @Test
    void aCp1252CsvIsReadByCodeInCodeOnlyMode() throws IOException {
        ParseResult result = step.parse(cp1252Context().withExtractionMode(ExtractionMode.CODE_ONLY));

        assertThat(result.provenance().strategy()).isEqualTo("DELIMITED");
        assertThat(result.table().getRows()).hasSize(6);
        verifyNoInteractions(aiFilesService);
    }

    @Test
    void aCp1252CsvStillFailsInAiOnlyMode() throws IOException {
        ParseContext context = cp1252Context().withExtractionMode(ExtractionMode.AI_ONLY);

        assertThatThrownBy(() -> step.parse(context)).isInstanceOf(UncheckedIOException.class);
    }

    private ParseContext cp1252Context() throws IOException {
        Path csv = ExtractionCorpus.generate("cp1252-customers.csv", tempDir);
        when(uploadStorage.resolve("cp1252-customers.csv")).thenReturn(csv);
        UncheckedIOException utf8Refused = new UncheckedIOException(
                "Could not read file", new MalformedInputException(1));
        when(documentParser.extract(csv)).thenThrow(utf8Refused);
        when(hintsBuilder.build(eq(csv), anyString(), anyString())).thenThrow(utf8Refused);
        when(batchPlanner.plan(anyString(), any(), any()))
                .thenAnswer(invocation -> TableExtractionPlan.singlePass(invocation.getArgument(0), 7, 4));
        return new ParseContext("cp1252-customers.csv", 1, 1, List.of(
                templateField("Customer Name"), templateField("City"),
                typedField("Balance", 2, "Number"), typedField("Opened", 3, "Date")));
    }

    // ---- still fatal in every mode (AC-EXT-06) --------------------------------------

    @Test
    void aMissingFileIsFatalInEveryMode() {
        when(uploadStorage.resolve("gone.csv")).thenReturn(tempDir.resolve("gone.csv"));
        for (ExtractionMode mode : ExtractionMode.values()) {
            assertThatThrownBy(() -> step.parse(new ParseContext("gone.csv", 1, 1, List.of(),
                    null, mode)))
                    .as(mode.name())
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void noExtractableTextIsFatalInEveryMode() {
        when(documentParser.extract(any())).thenReturn(new DocumentExtractionResult("  ", "local"));
        for (ExtractionMode mode : ExtractionMode.values()) {
            assertThatThrownBy(() -> step.parse(NOTES.withExtractionMode(mode)))
                    .as(mode.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No text could be extracted");
        }
        verify(aiFilesService, never()).sendPrompt(any());
    }

    private static TableExtractionPlan twoMarkdownBatches() {
        return new TableExtractionPlan(
                false,
                "| Emp ID | Name |",
                List.of("| E001 | Ada |"),
                List.of(
                        new TableRowBatch(1, 2, 1, 1, "| Emp ID | Name |\n| E001 | Ada |"),
                        new TableRowBatch(2, 2, 2, 2, "| E002 | Grace |")),
                2,
                2,
                "full text");
    }
}
