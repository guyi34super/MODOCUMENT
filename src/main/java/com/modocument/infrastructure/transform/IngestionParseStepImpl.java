package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.infrastructure.file.ExcelParsingProperties;
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
 * Parse stage of the ingestion pipeline. CSV goes straight to a table via code
 * (flat and reliable); every other supported format — Excel, pdf, Word, txt,
 * images — is extracted to text through its own flow (POI serialization /
 * docling / PDFBox / OCR) and tabularized by the LLM with structured output, so
 * they all return the same {@link TableData} JSON. Excel can be forced back onto
 * the deterministic parser with {@code modocument.parsing.excel.llm-enabled=false}.
 */
@Service
public class IngestionParseStepImpl implements IngestionParseStep {

    private final UploadStorage uploadStorage;
    private final TabularNormalizer tabularNormalizer;
    private final DocumentParser documentParser;
    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;
    private final PiiTokenizer tokenizer;
    private final ExcelParsingProperties excelParsing;

    public IngestionParseStepImpl(UploadStorage uploadStorage,
                                  TabularNormalizer tabularNormalizer,
                                  DocumentParser documentParser,
                                  PromptFactory promptFactory,
                                  AiFilesService aiFilesService,
                                  PiiTokenizer tokenizer,
                                  ExcelParsingProperties excelParsing) {
        this.uploadStorage = uploadStorage;
        this.tabularNormalizer = tabularNormalizer;
        this.documentParser = documentParser;
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
        this.tokenizer = tokenizer;
        this.excelParsing = excelParsing;
    }

    @Override
    public TableData parse(String fileName) {
        Path file = uploadStorage.resolve(fileName);
        if (!Files.exists(file)) {
            throw new NoSuchElementException(
                    "File '" + fileName + "' not found in uploads folder.");
        }

        String extension = extensionOf(fileName);
        // CSV is always parsed deterministically; Excel only when the LLM route
        // is disabled. Everything else falls through to the LLM extraction below.
        if (extension.equals(".csv") || (isExcel(extension) && !excelParsing.isLlmEnabled())) {
            return tabularNormalizer.normalize(file);
        }

        String text = documentParser.extractText(file);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from '" + fileName + "'.");
        }

        // The document's own text is the largest PII egress in the app, so the
        // shapes we can recognize (emails, phone numbers, long IDs, card
        // numbers) travel as placeholders and are restored in the answer.
        PiiTokenizer.Session session = tokenizer.newSession();
        PromptStrategy strategy = promptFactory.getPrompt("TableExtraction");
        String prompt = strategy.buildPrompt(
                new TableExtractionPromptInput(session.tokenizeText(text)));
        TableData extracted = aiFilesService.sendForObject(
                prompt, new ParameterizedTypeReference<TableData>() {
                });
        if (extracted == null || extracted.getRows() == null) {
            return extracted;
        }
        return new TableData(extracted.getColumns(), session.detokenizeRows(extracted.getRows()));
    }

    private static boolean isExcel(String extension) {
        return extension.equals(".xlsx") || extension.equals(".xls") || extension.equals(".xlsm");
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
