package com.modocument.infrastructure.file;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTextReaderTest {

    private final OcrService ocrService = mock(OcrService.class);
    private final VisionOcrService visionOcrService = mock(VisionOcrService.class);
    private final FileTextReader reader = new FileTextReader(ocrService, visionOcrService);

    @TempDir
    Path tempDir;

    private String pngFile() throws IOException {
        File file = tempDir.resolve("scan.png").toFile();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", file);
        return file.getPath();
    }

    private String imageOnlyPdf() throws IOException {
        File file = tempDir.resolve("scan.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", png);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, png.toByteArray(), "scan");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 50, 500);
            }
            document.save(file);
        }
        return file.getPath();
    }

    private String textPdf() throws IOException {
        File file = tempDir.resolve("text.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Plain text page");
                content.endText();
            }
            document.save(file);
        }
        return file.getPath();
    }

    @Test
    void imageUsesVisionOcrFirst() throws IOException {
        when(visionOcrService.extractText(any())).thenReturn("vision text");

        assertThat(reader.readAsText(pngFile())).isEqualTo("vision text");
        verify(ocrService, never()).extractText(any());
    }

    @Test
    void imageFallsBackToTesseractWhenVisionReturnsNothing() throws IOException {
        when(visionOcrService.extractText(any())).thenReturn("");
        when(ocrService.extractText(any())).thenReturn("tesseract text");

        assertThat(reader.readAsText(pngFile())).isEqualTo("tesseract text");
    }

    @Test
    void scannedPdfPageIsRasterizedForVisionOcr() throws IOException {
        when(visionOcrService.isEnabled()).thenReturn(true);
        when(visionOcrService.maxPagesPerDocument()).thenReturn(20);
        when(visionOcrService.renderDpi()).thenReturn(72);
        when(visionOcrService.extractText(any())).thenReturn("scanned content");

        assertThat(reader.readAsText(imageOnlyPdf())).contains("scanned content");
        verify(ocrService, never()).extractText(any());
    }

    @Test
    void scannedPdfFallsBackToEmbeddedImageOcrWhenVisionDisabled() throws IOException {
        when(visionOcrService.isEnabled()).thenReturn(false);
        when(ocrService.extractText(any())).thenReturn("tesseract page");

        assertThat(reader.readAsText(imageOnlyPdf())).contains("tesseract page");
        verify(visionOcrService, never()).extractText(any());
    }

    @Test
    void textPdfSkipsOcrEntirely() throws IOException {
        assertThat(reader.readAsText(textPdf())).contains("Plain text page");
        verify(visionOcrService, never()).extractText(any());
        verify(ocrService, never()).extractText(any());
    }

    /**
     * The rich Excel serialization is the whole point of routing spreadsheets
     * through the LLM: a merged label must be filled across every covered cell,
     * and a formula must surface its computed value — the two things the
     * deterministic parser gets wrong.
     */
    @Test
    void excelFillsMergedCellsAndEvaluatesFormulas() throws IOException {
        Path xlsx = tempDir.resolve("report.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Quarterly Report"); // title banner

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Person");
            header.createCell(2).setCellValue("Score");
            // "Person" is merged across the first two columns of the header row.
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));

            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("Ada");
            data.createCell(1).setCellValue("Lovelace");
            data.createCell(2).setCellFormula("1+2");

            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }

        String text = reader.readAsText(xlsx.toString());

        assertThat(text).contains("Quarterly Report");     // title row preserved for the LLM
        assertThat(text).contains("Person\tPerson\tScore"); // merged label filled across both cells
        assertThat(text).contains("Ada\tLovelace\t3");      // formula evaluated, not "1+2"
        assertThat(text).doesNotContain("1+2");
    }

    @Test
    void readsPlainTextFiles() throws IOException {
        Path txt = tempDir.resolve("notes.txt");
        Files.writeString(txt, "Name\tCity\nAda\tLondon\n");

        assertThat(reader.readAsText(txt.toString())).contains("Ada").contains("London");
    }
}
