package com.modocument.infrastructure.transform;

import com.modocument.application.dto.DocumentExtractionResult;
import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import com.modocument.application.dto.TableRowBatch;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.StructuredTableMaterializer;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.TableExtractionBatchPlanner;
import com.modocument.infrastructure.file.TableExtractionHintsBuilder;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private IngestionParseStepImpl step;

    @TempDir
    Path tempDir;

    private static final TableData LLM_TABLE = new TableData(
            List.of("Name", "City"),
            List.of(Map.of("Name", "Ada", "City", "London")));

    private static final ParseContext CONTEXT = new ParseContext(
            "book.xlsx", 1, 1, List.of(templateField("FirstName")));

    private static TemplateFields templateField(String name) {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        return field;
    }

    @BeforeEach
    void setUp() throws IOException {
        step = new IngestionParseStepImpl(uploadStorage, tabularNormalizer, documentParser,
                promptFactory, aiFilesService, tokenizer, excelParsing, parsingProperties,
                hintsBuilder, batchPlanner, structuredMaterializer);

        for (String name : List.of("book.xlsx", "book.xlsm", "people.csv", "notes.txt", "report.pdf")) {
            Path file = tempDir.resolve(name);
            Files.writeString(file, "x");
            when(uploadStorage.resolve(name)).thenReturn(file);
        }

        PromptStrategy strategy = mock(PromptStrategy.class);
        when(strategy.buildPrompt(any())).thenReturn("prompt");
        when(promptFactory.getPrompt("TableExtraction")).thenReturn(strategy);
        when(documentParser.extract(any())).thenReturn(
                new DocumentExtractionResult("Row 1: Name\tCity\nRow 2: Ada\tLondon", "local"));
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".xlsx", "local", 2, List.of("Name", "City"), 1));
        when(batchPlanner.plan(anyString(), any(), any()))
                .thenReturn(TableExtractionPlan.singlePass("Row 1: Name\tCity\nRow 2: Ada\tLondon", 1, 2));
        when(structuredMaterializer.tryMaterialize(any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        doReturn(LLM_TABLE).when(aiFilesService).sendForObject(any(PromptEnvelope.class), any());
    }

    @Test
    void excelGoesThroughTheLlmByDefault() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class))).thenReturn("""
                {"columns":["Name","City"],"mapping":{"Name":"FirstName","City":null},
                "rows":[{"Name":"Ada","City":"London"}]}
                """);

        ParseResult result = step.parse(CONTEXT);

        assertThat(result.table().getColumns()).containsExactly("Name", "City");
        assertThat(result.suggestedMapping()).containsEntry("Name", "FirstName");
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
        verifyNoInteractions(aiFilesService);
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

    @Test
    void llmJsonFailureThrowsInsteadOfPoiFallback() {
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new RuntimeException(
                        "com.fasterxml.jackson.databind.JsonMappingException: Unexpected end-of-input"));

        assertThatThrownBy(() -> step.parse(CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM table extraction failed");
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void combinedBatchedUsesExtractAndMapForFirstBatchOnly() {
        when(hintsBuilder.build(any(), anyString(), anyString()))
                .thenReturn(new TableExtractionHints(".xlsx", "local", 2,
                        List.of("Emp ID", "Name"), 2));
        when(batchPlanner.plan(anyString(), any(), any())).thenReturn(new TableExtractionPlan(
                false,
                "| Emp ID | Name |",
                List.of("| E001 | Ada |"),
                List.of(
                        new TableRowBatch(1, 2, 1, 1, "| Emp ID | Name |\n| E001 | Ada |"),
                        new TableRowBatch(2, 2, 2, 2, "| E002 | Grace |")),
                2,
                2,
                "full text"));
        when(aiFilesService.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("""
                        {"columns":["Emp ID","Name"],"mapping":{"Emp ID":"FirstName","Name":null},
                        "rows":[{"Emp ID":"E001","Name":"Ada"}]}
                        """)
                .thenReturn("{\"rows\":[{\"Emp ID\":\"E002\",\"Name\":\"Grace\"}]}");

        ParseResult result = step.parse(CONTEXT);

        assertThat(result.table().getRows()).hasSize(2);
        assertThat(result.suggestedMapping()).containsEntry("Emp ID", "FirstName");
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
}
