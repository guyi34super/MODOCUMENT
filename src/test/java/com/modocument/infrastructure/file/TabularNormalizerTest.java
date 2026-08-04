package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
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
    void repeatedHeaderKeepsBothColumns() throws Exception {
        Path csv = tempDir.resolve("duplicates.csv");
        Files.writeString(csv, "Email,Email\nwork@x.com,home@x.com\n");

        TableData table = normalizer.normalize(csv);

        assertThat(table.getColumns()).containsExactly("Email", "Email (2)");
        assertThat(table.getRows().get(0))
                .containsEntry("Email", "work@x.com")
                .containsEntry("Email (2)", "home@x.com");
    }

    @Test
    void blankHeaderGetsAPositionalName() throws Exception {
        Path csv = tempDir.resolve("blank-header.csv");
        Files.writeString(csv, "Name,\nAda,36\n");

        TableData table = normalizer.normalize(csv);

        assertThat(table.getColumns()).containsExactly("Name", "Column2");
        assertThat(table.getRows().get(0)).containsEntry("Column2", "36");
    }

    @Test
    void stripsByteOrderMarkFromTheFirstColumnName() throws Exception {
        Path csv = tempDir.resolve("bom.csv");
        Files.writeString(csv, "﻿FirstName,LastName\nAda,Lovelace\n");

        TableData table = normalizer.normalize(csv);

        assertThat(table.getColumns()).containsExactly("FirstName", "LastName");
        assertThat(table.getRows().get(0)).containsEntry("FirstName", "Ada");
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

    @Test
    void skipsTitleBannerRowBeforeRealHeaders() throws Exception {
        Path xlsx = tempDir.resolve("banner.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("NOVA DYNAMICS LTD — Employee roster");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 8));

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Emp ID");
            header.createCell(1).setCellValue("Full Name");
            header.createCell(2).setCellValue("Department");
            header.createCell(3).setCellValue("Job Title");
            header.createCell(4).setCellValue("Location");
            header.createCell(5).setCellValue("Start Year");
            header.createCell(6).setCellValue("Salary (MUR)");
            header.createCell(7).setCellValue("Status");
            header.createCell(8).setCellValue("Manager");

            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("E001");
            data.createCell(1).setCellValue("Ada Lovelace");
            data.createCell(2).setCellValue("Engineering");
            data.createCell(3).setCellValue("Developer");
            data.createCell(4).setCellValue("Port Louis");
            data.createCell(5).setCellValue("2019");
            data.createCell(6).setCellValue("45000");
            data.createCell(7).setCellValue("Active");
            data.createCell(8).setCellValue("Grace Hopper");

            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }

        TableData table = normalizer.normalize(xlsx);

        assertThat(table.getColumns()).containsExactly(
                "Emp ID", "Full Name", "Department", "Job Title", "Location",
                "Start Year", "Salary (MUR)", "Status", "Manager");
        assertThat(table.getRows()).hasSize(1);
        assertThat(table.getRows().get(0))
                .containsEntry("Emp ID", "E001")
                .containsEntry("Full Name", "Ada Lovelace")
                .doesNotContainKey("NOVA DYNAMICS LTD — Employee roster");
        assertThat(table.getRows().get(0).get("Emp ID")).doesNotContain("NOVA DYNAMICS");
    }
}
