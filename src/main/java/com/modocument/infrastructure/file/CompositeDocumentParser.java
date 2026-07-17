package com.modocument.infrastructure.file;

import com.modocument.application.port.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Primary {@link DocumentParser}: docling-serve when configured (rich layout
 * handling + OCR for scans), transparently falling back to the built-in
 * readers (POI/Commons CSV/PDFBox) when docling is disabled, does not support
 * the format, or errors. Inside the built-in readers, images and scanned PDF
 * pages go through {@link VisionOcrService} (OpenAI vision model) first and
 * {@link OcrService} (Tess4J) as offline fallback — the same
 * graceful-degradation philosophy throughout.
 */
@Component
public class CompositeDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(CompositeDocumentParser.class);

    /** Formats the built-in FileTextReader can handle. */
    private static final Set<String> LOCAL_EXTENSIONS = Set.of(
            ".xlsx", ".xls", ".csv", ".pdf", ".docx", ".doc",
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

    @Override
    public String extractText(Path file) {
        String extension = extensionOf(file);
        if (doclingClient.isEnabled() && doclingClient.supports(extension)) {
            try {
                return doclingClient.convertToMarkdown(file);
            } catch (Exception e) {
                log.warn("docling conversion failed for {}, falling back to local readers: {}",
                        file.getFileName(), e.getMessage());
            }
        }
        return fileTextReader.readAsText(file.toString());
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
