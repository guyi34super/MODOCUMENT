package com.modocument.infrastructure.transform.code;

import com.modocument.application.validation.SafeRegex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-document state shared by the strategies of one extraction: the input,
 * the text views (pipeline text, re-decoded delimited bytes, PDF layout
 * text — each computed at most once), the field matcher and the regex budget.
 */
final class ExtractionContext {

    private static final Logger log = LoggerFactory.getLogger(ExtractionContext.class);

    /** Delimited-text types whose raw bytes are re-decoded (BOM, cp1252). */
    static final Set<String> DELIMITED_TYPES = Set.of(".csv", ".tsv", ".txt", ".text", ".psv", ".tab");

    /** Raw bytes beyond this are not re-read; the pipeline text is used instead. */
    static final long MAX_RAW_BYTES = 64L * 1024 * 1024;

    private final ExtractionInput input;
    private final SafeRegex.Budget budget;
    private final FieldMatcher matcher;
    private final List<String> notes = new ArrayList<>();
    private final String text;

    private boolean delimitedLoaded;
    private String delimitedText;
    private String delimitedNote;
    private boolean layoutLoaded;
    private String layoutText;

    ExtractionContext(ExtractionInput input, SafeRegex.Budget budget) {
        this.input = input;
        this.budget = budget;
        this.text = TextDecoding.stripBom(input.text());
        this.matcher = new FieldMatcher(input.fields(), input.globalAliases(), budget, notes);
    }

    ExtractionInput input() {
        return input;
    }

    SafeRegex.Budget budget() {
        return budget;
    }

    FieldMatcher matcher() {
        return matcher;
    }

    /** Document-level notes (rejected hint patterns …), appended to every candidate. */
    List<String> notes() {
        return notes;
    }

    /** The pipeline text with any BOM removed. */
    String text() {
        return text;
    }

    String fileType() {
        return input.fileType();
    }

    Path file() {
        return input.file();
    }

    boolean hasReadableFile() {
        Path file = input.file();
        return file != null && Files.isRegularFile(file);
    }

    /**
     * The text to sniff for delimiters: for delimited file types the raw
     * bytes re-decoded (UTF-8, BOM-aware, windows-1252 fallback), otherwise
     * the pipeline text.
     */
    String delimitedText() {
        if (!delimitedLoaded) {
            delimitedLoaded = true;
            delimitedText = text;
            if (DELIMITED_TYPES.contains(fileType()) && hasReadableFile()) {
                try {
                    if (Files.size(input.file()) <= MAX_RAW_BYTES) {
                        TextDecoding.Decoded decoded = TextDecoding.decode(Files.readAllBytes(input.file()));
                        delimitedText = decoded.text();
                        delimitedNote = "decoded as " + decoded.charset().name()
                                + (decoded.hadBom() ? " (byte-order mark removed)" : "");
                    }
                } catch (IOException e) {
                    log.debug("Raw delimited bytes unreadable, using the extracted text: {}", e.getMessage());
                }
            }
        }
        return delimitedText;
    }

    /** How {@link #delimitedText()} was decoded, or null when the pipeline text was used. */
    String delimitedNote() {
        delimitedText();
        return delimitedNote;
    }

    /** Position-preserving text for PDFs, or null for other types or unreadable files. */
    String pdfLayoutText() {
        if (!layoutLoaded) {
            layoutLoaded = true;
            if (".pdf".equals(fileType()) && hasReadableFile()) {
                try {
                    layoutText = PdfLayoutText.read(input.file());
                } catch (IOException | RuntimeException e) {
                    log.debug("PDF layout text unavailable: {}", e.getMessage());
                }
            }
        }
        return layoutText;
    }
}
