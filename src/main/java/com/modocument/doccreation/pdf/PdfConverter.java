package com.modocument.doccreation.pdf;

import java.nio.file.Path;

/** Converts a generated .docx to PDF. Implementations may be unavailable at runtime. */
public interface PdfConverter {

    boolean isAvailable();

    /**
     * @return the PDF bytes
     * @throws IllegalStateException when conversion is unavailable or fails —
     *     surfaced as 409 with the message, the .docx download always works.
     */
    byte[] convert(Path docxFile);
}
