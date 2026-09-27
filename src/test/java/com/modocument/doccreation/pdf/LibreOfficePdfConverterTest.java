package com.modocument.doccreation.pdf;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibreOfficePdfConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void aMissingBinaryMeansUnavailableAndAClearConvertError() {
        LibreOfficePdfConverter converter =
                new LibreOfficePdfConverter("/definitely/not/a/real/soffice", 5);

        assertThat(converter.isAvailable()).isFalse();
        assertThatThrownBy(() -> converter.convert(Path.of("whatever.docx")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("download the .docx instead");
    }

    /**
     * Real conversion through the machine's LibreOffice — the exact production
     * path (the Docker image ships soffice). Skipped automatically on machines
     * without it, e.g. bare CI runners.
     */
    @Test
    void convertsARealDocxToARealPdfWhenLibreOfficeIsInstalled() throws IOException {
        LibreOfficePdfConverter converter = new LibreOfficePdfConverter("soffice", 120);
        assumeTrue(converter.isAvailable(), "LibreOffice not installed here — skipping live conversion");

        Path docx = tempDir.resolve("sample.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Hello, PDF world.");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        byte[] pdf = converter.convert(docx);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }
}
