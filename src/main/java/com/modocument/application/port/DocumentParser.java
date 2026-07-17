package com.modocument.application.port;

import java.nio.file.Path;

/**
 * Turns an uploaded document (pdf, office formats, csv, images/scans, ...)
 * into plain text/markdown suitable for LLM prompts.
 */
public interface DocumentParser {

    /** Lower-cased extension including the dot, e.g. ".pdf". */
    boolean supports(String extension);

    String extractText(Path file);
}
