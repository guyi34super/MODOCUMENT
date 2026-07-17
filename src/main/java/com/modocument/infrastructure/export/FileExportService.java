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

/** Serializes an ingested table to CSV, JSON, or XLSX bytes for download/archive. */
@Service
public class FileExportService {

    public enum Format {
        CSV("text/csv", ".csv"),
        JSON("application/json", ".json"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");

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
                        "Unknown export format '" + value + "'; use csv, json, or xlsx.");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public byte[] export(TableData table, Format format) {
        try {
            return switch (format) {
                case CSV -> toCsv(table);
                case JSON -> MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(table.getRows());
                case XLSX -> toXlsx(table);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize export.", e);
        }
    }

    private static byte[] toCsv(TableData table) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> columns = table.getColumns();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(columns.toArray(new String[0]))
                .build();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
            for (Map<String, String> row : table.getRows()) {
                printer.printRecord(columns.stream().map(c -> row.getOrDefault(c, "")).toList());
            }
        }
        return out.toByteArray();
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
