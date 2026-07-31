package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.TabularNormalizer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Routing is the crux: CSV stays deterministic, Excel/txt (and other rich
 * formats) go through the same LLM extraction so every format yields one
 * TableData JSON — and the {@code llm-enabled=false} toggle sends Excel back to
 * the deterministic parser for keyless operation.
 */
class IngestionParseStepImplTest {

    private final UploadStorage uploadStorage = mock(UploadStorage.class);
    private final TabularNormalizer tabularNormalizer = mock(TabularNormalizer.class);
    private final DocumentParser documentParser = mock(DocumentParser.class);
    private final PromptFactory promptFactory = mock(PromptFactory.class);
    private final AiFilesService aiFilesService = mock(AiFilesService.class);
    private final PiiTokenizer tokenizer = new PiiTokenizer(new PrivacyProperties());
    private final ExcelParsingProperties excelParsing = new ExcelParsingProperties();

    private IngestionParseStepImpl step;

    @TempDir
    Path tempDir;

    private static final TableData LLM_TABLE = new TableData(
            List.of("Name", "City"),
            List.of(Map.of("Name", "Ada", "City", "London")));

    @BeforeEach
    void setUp() throws IOException {
        step = new IngestionParseStepImpl(uploadStorage, tabularNormalizer, documentParser,
                promptFactory, aiFilesService, tokenizer, excelParsing);

        // Every routed file must resolve to something that exists on disk.
        for (String name : List.of("book.xlsx", "book.xlsm", "people.csv", "notes.txt")) {
            Path file = tempDir.resolve(name);
            Files.writeString(file, "x");
            when(uploadStorage.resolve(name)).thenReturn(file);
        }

        PromptStrategy strategy = mock(PromptStrategy.class);
        when(strategy.buildPrompt(any())).thenReturn("prompt");
        when(promptFactory.getPrompt("TableExtraction")).thenReturn(strategy);
        when(documentParser.extractText(any())).thenReturn("Row 1: Name\tCity");
        doReturn(LLM_TABLE).when(aiFilesService).sendForObject(anyString(), any());
    }

    @Test
    void excelGoesThroughTheLlmByDefault() {
        TableData result = step.parse("book.xlsx");

        assertThat(result.getColumns()).containsExactly("Name", "City");
        assertThat(result.getRows()).hasSize(1);
        verify(documentParser).extractText(any());
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void macroWorkbooksAlsoGoThroughTheLlm() {
        step.parse("book.xlsm");
        verify(documentParser).extractText(any());
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void plainTextGoesThroughTheLlm() {
        TableData result = step.parse("notes.txt");

        assertThat(result.getColumns()).containsExactly("Name", "City");
        verify(documentParser).extractText(any());
        verify(tabularNormalizer, never()).normalize(any());
    }

    @Test
    void csvStaysDeterministic() {
        TableData csvTable = new TableData(List.of("A"), List.of(Map.of("A", "1")));
        when(tabularNormalizer.normalize(any())).thenReturn(csvTable);

        assertThat(step.parse("people.csv")).isSameAs(csvTable);
        verify(tabularNormalizer).normalize(any());
        verifyNoInteractions(documentParser, aiFilesService);
    }

    @Test
    void excelFallsBackToDeterministicWhenLlmDisabled() {
        excelParsing.setLlmEnabled(false);
        TableData deterministic = new TableData(List.of("A"), List.of(Map.of("A", "1")));
        when(tabularNormalizer.normalize(any())).thenReturn(deterministic);

        assertThat(step.parse("book.xlsx")).isSameAs(deterministic);
        verify(tabularNormalizer).normalize(any());
        verifyNoInteractions(documentParser, aiFilesService);
    }
}
