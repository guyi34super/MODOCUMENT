package com.modocument.doccreation.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibreOfficeDocxConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void aMissingBinaryMeansUnavailableAndAClearConvertError() {
        LibreOfficeDocxConverter converter =
                new LibreOfficeDocxConverter("/definitely/not/a/real/soffice", 5);

        assertThat(converter.isAvailable()).isFalse();
        assertThatThrownBy(() -> converter.convertPdfToDocx(Path.of("whatever.pdf")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upload a .docx blueprint instead");
    }

    @Test
    void convertsATextPdfToDocxWhenLibreOfficeIsInstalled() throws IOException {
        LibreOfficeDocxConverter converter = new LibreOfficeDocxConverter("soffice", 120);
        assumeTrue(converter.isAvailable(), "LibreOffice not installed here — skipping live conversion");

        Path pdf = tempDir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Blueprint section one");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        byte[] docx = converter.convertPdfToDocx(pdf);
        assertThat(docx).isNotEmpty();
        try (InputStream in = new java.io.ByteArrayInputStream(docx);
             XWPFDocument doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs()).isNotEmpty();
        }
    }
}
