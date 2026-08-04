package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tiered fast path: deterministically materializes tables when structure is
 * unambiguous, falling back to LLM extraction when validation fails.
 */
@Component
public class StructuredTableMaterializer {

    private static final Logger log = LoggerFactory.getLogger(StructuredTableMaterializer.class);

    private final MarkdownTableMaterializer markdownMaterializer;
    private final RowLineTableMaterializer rowLineMaterializer;
    private final TabularNormalizer tabularNormalizer;
    private final ParsingProperties parsingProperties;

    public StructuredTableMaterializer(MarkdownTableMaterializer markdownMaterializer,
                                         RowLineTableMaterializer rowLineMaterializer,
                                         TabularNormalizer tabularNormalizer,
                                         ParsingProperties parsingProperties) {
        this.markdownMaterializer = markdownMaterializer;
        this.rowLineMaterializer = rowLineMaterializer;
        this.tabularNormalizer = tabularNormalizer;
        this.parsingProperties = parsingProperties;
    }

    public Optional<TableData> tryMaterialize(Path file, String text,
                                                TableExtractionHints hints,
                                                TableExtractionPlan plan) {
        if (!parsingProperties.isDeterministicEnabled()) {
            return Optional.empty();
        }
        if (!isEligible(hints, plan)) {
            return Optional.empty();
        }

        List<String> columns = uniqueColumns(hints.candidateHeaderCells());
        String extension = extensionOf(file);

        if (tabularNormalizer.supports(extension)) {
            Optional<TableData> fromFile = tryFromFile(file, hints);
            if (fromFile.isPresent()) {
                log.info("Deterministic table extraction via direct file parse ({} rows)",
                        fromFile.get().getRows().size());
                return fromFile;
            }
        }

        if (markdownMaterializer.isMarkdownTableText(text)) {
            Optional<TableData> fromMarkdown = tryFromMarkdown(text, columns, hints);
            if (fromMarkdown.isPresent()) {
                log.info("Deterministic table extraction via markdown ({} rows)",
                        fromMarkdown.get().getRows().size());
                return fromMarkdown;
            }
        }

        if (rowLineMaterializer.isRowLineText(text)) {
            Optional<TableData> fromRows = tryFromRowLines(text, columns, hints);
            if (fromRows.isPresent()) {
                log.info("Deterministic table extraction via row lines ({} rows)",
                        fromRows.get().getRows().size());
                return fromRows;
            }
        }

        return Optional.empty();
    }

    private Optional<TableData> tryFromFile(Path file, TableExtractionHints hints) {
        try {
            TableData data = tabularNormalizer.normalize(file);
            return validate(data, hints) ? Optional.of(data) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Direct file materialization failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TableData> tryFromMarkdown(String text, List<String> columns,
                                                TableExtractionHints hints) {
        List<Map<String, String>> allRows = markdownMaterializer.parseRows(text, columns);
        List<Map<String, String>> dataRows = skipHeaderRowIfPresent(allRows, columns);
        TableData data = new TableData(columns, dataRows);
        return validate(data, hints) ? Optional.of(data) : Optional.empty();
    }

    private Optional<TableData> tryFromRowLines(String text, List<String> columns,
                                                  TableExtractionHints hints) {
        List<Map<String, String>> rows = rowLineMaterializer.parseRows(text, columns);
        TableData data = new TableData(columns, rows);
        return validate(data, hints) ? Optional.of(data) : Optional.empty();
    }

    static boolean isEligible(TableExtractionHints hints, TableExtractionPlan plan) {
        if (hints == null || plan == null) {
            return false;
        }
        List<String> headers = hints.candidateHeaderCells();
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        int expectedColumns = hints.expectedColumnCount();
        if (expectedColumns <= 0) {
            return false;
        }
        if (headers.size() != expectedColumns) {
            return false;
        }
        if (plan.estimatedColumns() > 0 && headers.size() != plan.estimatedColumns()) {
            return false;
        }
        return plan.estimatedDataRows() > 0 || hints.dataRowCount() > 0;
    }

    static boolean validate(TableData data, TableExtractionHints hints) {
        if (data == null || data.getColumns() == null || data.getRows() == null) {
            return false;
        }
        if (data.getColumns().isEmpty() || data.getRows().isEmpty()) {
            return false;
        }
        int expectedRows = hints.dataRowCount();
        if (expectedRows <= 0) {
            return true;
        }
        int actual = data.getRows().size();
        return Math.abs(actual - expectedRows) <= 1;
    }

    private static List<Map<String, String>> skipHeaderRowIfPresent(
            List<Map<String, String>> rows, List<String> columns) {
        if (rows.isEmpty() || columns.isEmpty()) {
            return rows;
        }
        Map<String, String> first = rows.get(0);
        boolean looksLikeHeader = true;
        for (String column : columns) {
            String value = first.getOrDefault(column, "");
            if (!value.equalsIgnoreCase(column) && !column.equalsIgnoreCase(value.trim())) {
                looksLikeHeader = false;
                break;
            }
        }
        if (looksLikeHeader && rows.size() > 1) {
            return rows.subList(1, rows.size());
        }
        return rows;
    }

    private static List<String> uniqueColumns(List<String> headers) {
        List<String> columns = new ArrayList<>(headers.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i) == null ? "" : headers.get(i).trim();
            if (header.isBlank()) {
                header = "Column" + (i + 1);
            }
            int count = seen.merge(header, 1, Integer::sum);
            columns.add(count == 1 ? header : header + " (" + count + ")");
        }
        return columns;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
