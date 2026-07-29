package com.modocument.infrastructure.export;

import com.modocument.application.dto.TableData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileExportServiceTest {

    private final FileExportService service = new FileExportService();

    private static final TableData TABLE = new TableData(
            List.of("Name", "City"),
            List.of(Map.of("Name", "Ada", "City", "London"),
                    Map.of("Name", "Grace", "City", "Arlington")));

    @Test
    void exportsCsvWithHeaderAndRows() {
        String csv = new String(service.export(TABLE, FileExportService.Format.CSV),
                StandardCharsets.UTF_8);

        assertThat(csv).startsWith("Name,City");
        assertThat(csv).contains("Ada,London").contains("Grace,Arlington");
    }

    @Test
    void exportsJsonRows() {
        String json = new String(service.export(TABLE, FileExportService.Format.JSON),
                StandardCharsets.UTF_8);

        assertThat(json).contains("\"Name\"").contains("Ada").contains("Arlington");
    }

    @Test
    void exportsReadableXlsx() throws Exception {
        byte[] bytes = service.export(TABLE, FileExportService.Format.XLSX);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Name");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("London");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Grace");
        }
    }

    @Test
    void exportsTsvWithTabDelimiters() {
        String tsv = new String(service.export(TABLE, FileExportService.Format.TSV),
                StandardCharsets.UTF_8);

        assertThat(tsv).startsWith("Name\tCity");
        assertThat(tsv).contains("Ada\tLondon").contains("Grace\tArlington");
    }

    @Test
    void exportsEscapedXmlWithColumnNameAttributes() {
        TableData awkward = new TableData(
                List.of("Full Name", "Note"),
                List.of(Map.of("Full Name", "Ada <Lovelace>", "Note", "a & b")));

        String xml = new String(service.export(awkward, FileExportService.Format.XML),
                StandardCharsets.UTF_8);

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("<col name=\"Full Name\">Ada &lt;Lovelace&gt;</col>");
        assertThat(xml).contains("<col name=\"Note\">a &amp; b</col>");
        assertThat(xml).contains("<rows>").contains("</rows>");
    }

    @Test
    void parseRejectsUnknownFormat() {
        assertThatThrownBy(() -> FileExportService.Format.parse("pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("csv, json, xlsx, tsv, or xml");
    }
}
