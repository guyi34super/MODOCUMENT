package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.PromptStrategy;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Parse stage of the ingestion pipeline: csv/xlsx go straight to a table via
 * code; every other supported format is extracted to text (docling/PDFBox/
 * OCR/POI) and tabularized by the LLM with structured output.
 */
@Service
public class IngestionParseStepImpl implements IngestionParseStep {

    private final UploadStorage uploadStorage;
    private final TabularNormalizer tabularNormalizer;
    private final DocumentParser documentParser;
    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;

    public IngestionParseStepImpl(UploadStorage uploadStorage,
                                  TabularNormalizer tabularNormalizer,
                                  DocumentParser documentParser,
                                  PromptFactory promptFactory,
                                  AiFilesService aiFilesService) {
        this.uploadStorage = uploadStorage;
        this.tabularNormalizer = tabularNormalizer;
        this.documentParser = documentParser;
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
    }

    @Override
    public TableData parse(String fileName) {
        Path file = uploadStorage.resolve(fileName);
        if (!Files.exists(file)) {
            throw new NoSuchElementException(
                    "File '" + fileName + "' not found in uploads folder.");
        }

        String extension = extensionOf(fileName);
        if (tabularNormalizer.supports(extension)) {
            return tabularNormalizer.normalize(file);
        }

        String text = documentParser.extractText(file);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from '" + fileName + "'.");
        }

        PromptStrategy strategy = promptFactory.getPrompt("TableExtraction");
        String prompt = strategy.buildPrompt(new TableExtractionPromptInput(text));
        return aiFilesService.sendForObject(prompt, new ParameterizedTypeReference<TableData>() {
        });
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
