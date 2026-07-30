package com.modocument.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.TableData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Serializes an ingested table to CSV, JSON, XLSX, TSV, or XML bytes for download/archive. */
@Service
public class FileExportService {

    public enum Format {
        CSV("text/csv", ".csv"),
        JSON("application/json", ".json"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
        TSV("text/tab-separated-values", ".tsv"),
        XML("application/xml", ".xml");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        public static Format parse(String value) {
            try {
                return Format.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown export format '" + value + "'; use csv, json, xlsx, tsv, or xml.");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public byte[] export(TableData table, Format format) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            throw new IllegalStateException(
                    "There are no columns to export; map at least one column to a template field.");
        }
        try {
            return switch (format) {
                case CSV -> toDelimited(table, CSVFormat.DEFAULT);
                case JSON -> MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(table.getRows());
                case XLSX -> toXlsx(table);
                case TSV -> toDelimited(table, CSVFormat.TDF);
                case XML -> toXml(table);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize export.", e);
        }
    }

    private static byte[] toDelimited(TableData table, CSVFormat base) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> columns = table.getColumns();
        CSVFormat format = base.builder()
                .setHeader(columns.stream().map(FileExportService::neutralizeFormula)
                        .toArray(String[]::new))
                .build();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
            for (Map<String, String> row : table.getRows()) {
                printer.printRecord(columns.stream()
                        .map(c -> neutralizeFormula(row.getOrDefault(c, "")))
                        .toList());
            }
        }
        return out.toByteArray();
    }

    /**
     * Excel and Sheets execute a cell that starts with = + - or @, so an
     * ingested value like {@code =HYPERLINK(...)} would run when the exported
     * file is opened. A leading apostrophe makes it text again.
     */
    static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
    }

    /**
     * Columns become {@code <col name="...">} attributes rather than element
     * names because column names may contain characters (spaces, slashes)
     * that are not legal in XML element names.
     */
    private static byte[] toXml(TableData table) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n");
        for (Map<String, String> row : table.getRows()) {
            xml.append("  <row>\n");
            for (String column : table.getColumns()) {
                xml.append("    <col name=\"").append(escapeAttr(column)).append("\">")
                        .append(escapeText(row.getOrDefault(column, "")))
                        .append("</col>\n");
            }
            xml.append("  </row>\n");
        }
        xml.append("</rows>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Escapes markup and drops the characters XML 1.0 simply cannot carry
     * (control codes other than tab/newline), which otherwise produced a file
     * no parser would accept. CR is escaped rather than dropped because an
     * unescaped one is silently normalized to LF on re-read.
     */
    private static String escapeText(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '\r' -> escaped.append("&#13;");
                case '\n', '\t' -> escaped.append(c);
                default -> {
                    if (c >= 0x20 || Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Attribute values additionally need the quote escaped, and newlines/tabs
     * spelled out — a literal one is normalized to a space when the attribute
     * is read back, so a column name containing it would come out different.
     */
    private static String escapeAttr(String value) {
        return escapeText(value)
                .replace("\"", "&quot;")
                .replace("\n", "&#10;")
                .replace("\t", "&#9;");
    }

    private static byte[] toXlsx(TableData table) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Data");
            List<String> columns = table.getColumns();

            Row header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                header.createCell(c).setCellValue(columns.get(c));
            }
            int r = 1;
            for (Map<String, String> rowData : table.getRows()) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < columns.size(); c++) {
                    row.createCell(c).setCellValue(rowData.getOrDefault(columns.get(c), ""));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
