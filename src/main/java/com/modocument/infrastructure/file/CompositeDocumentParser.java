package com.modocument.infrastructure.file;

import com.modocument.application.dto.DocumentExtractionResult;
import com.modocument.application.port.DocumentParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Text extraction for the LLM pipeline: built-in readers only (POI, PDFBox,
 * CSV, OCR). Docling is not used — extracted plain text is always structured
 * by the LLM downstream.
 */
@Component
public class CompositeDocumentParser implements DocumentParser {

    /** Formats the built-in {@link FileTextReader} can handle. */
    private static final Set<String> LOCAL_EXTENSIONS = Set.of(
            ".xlsx", ".xls", ".xlsm", ".csv", ".txt", ".pdf", ".docx", ".doc",
            ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp");

    private final DoclingClient doclingClient;
    private final FileTextReader fileTextReader;

    public CompositeDocumentParser(DoclingClient doclingClient, FileTextReader fileTextReader) {
        this.doclingClient = doclingClient;
        this.fileTextReader = fileTextReader;
    }

    @Override
    public boolean supports(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return LOCAL_EXTENSIONS.contains(ext)
                || (doclingClient.isEnabled() && doclingClient.supports(ext));
    }

    public Set<String> supportedExtensions() {
        if (!doclingClient.isEnabled()) {
            return LOCAL_EXTENSIONS;
        }
        Set<String> union = new java.util.HashSet<>(LOCAL_EXTENSIONS);
        union.addAll(doclingClient.supportedExtensions());
        return union;
    }

    @Override
    public String extractText(Path file) {
        return extract(file).text();
    }

    @Override
    public DocumentExtractionResult extract(Path file) {
        String extension = extensionOf(file);
        if (LOCAL_EXTENSIONS.contains(extension)) {
            return new DocumentExtractionResult(
                    fileTextReader.readAsText(file.toString()), "local");
        }
        if (doclingClient.isEnabled() && doclingClient.supports(extension)) {
            try {
                return new DocumentExtractionResult(
                        doclingClient.convertToMarkdown(file), "docling");
            } catch (Exception e) {
                throw new IllegalStateException("'" + file.getFileName()
                        + "' could not be converted: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException(
                "Unsupported file type '" + extension + "' for '" + file.getFileName() + "'.");
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
