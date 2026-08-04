package com.modocument.application.port;

import com.modocument.application.dto.DocumentExtractionResult;

import java.nio.file.Path;

/**
 * Turns an uploaded document (pdf, office formats, csv, images/scans, ...)
 * into plain text/markdown suitable for LLM prompts.
 */
public interface DocumentParser {

    /** Lower-cased extension including the dot, e.g. ".pdf". */
    boolean supports(String extension);

    String extractText(Path file);

    /** Text plus which reader produced it ({@code docling} or {@code local}). */
    default DocumentExtractionResult extract(Path file) {
        return new DocumentExtractionResult(extractText(file), "local");
    }
}
