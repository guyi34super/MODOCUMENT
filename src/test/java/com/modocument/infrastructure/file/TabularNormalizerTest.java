package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TabularNormalizerTest {

    private final TabularNormalizer normalizer = new TabularNormalizer();

    @TempDir
    Path tempDir;

    @Test
    void supportsOnlyStructuredFormats() {
        assertThat(normalizer.supports(".csv")).isTrue();
        assertThat(normalizer.supports(".XLSX")).isTrue();
        assertThat(normalizer.supports(".pdf")).isFalse();
        assertThat(normalizer.supports(null)).isFalse();
    }

    @Test
    void normalizesCsvIntoColumnsAndRows() throws Exception {
        Path csv = tempDir.resolve("people.csv");
        Files.writeString(csv, "FirstName,LastName\nAda,Lovelace\nGrace,Hopper\n");

        TableData table = normalizer.normalize(csv);

        assertThat(table.getColumns()).containsExactly("FirstName", "LastName");
        assertThat(table.getRows()).hasSize(2);
        assertThat(table.getRows().get(0)).containsEntry("FirstName", "Ada");
        assertThat(table.getRows().get(1)).containsEntry("LastName", "Hopper");
    }

    @Test
    void normalizesXlsxFirstSheetWithHeaderRow() throws Exception {
        Path xlsx = tempDir.resolve("people.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Age");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Ada");
            data.createCell(1).setCellValue(36);
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }

        TableData table = normalizer.normalize(xlsx);

        assertThat(table.getColumns()).containsExactly("Name", "Age");
        assertThat(table.getRows()).hasSize(1);
        assertThat(table.getRows().get(0)).containsEntry("Name", "Ada").containsEntry("Age", "36");
    }
}
