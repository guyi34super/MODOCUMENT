package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import com.modocument.application.dto.TableRowBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits extracted document text into a schema pass plus row batches for LLM
 * table extraction, or returns a single-pass plan for small/non-tabular docs.
 */
@Component
public class TableExtractionBatchPlanner {

    private static final Pattern ROW_LINE = Pattern.compile("^Row\\s+(\\d+):\\s*(.*)$");
    private static final int SAMPLE_ROWS = 5;

    public TableExtractionPlan plan(String extractedText, TableExtractionHints hints,
                                    ParsingProperties properties) {
        if (extractedText == null || extractedText.isBlank()) {
            return TableExtractionPlan.singlePass("", 0, 0);
        }

        TabularSlice slice = sliceFromMarkdown(extractedText);
        if (slice == null) {
            slice = sliceFromRowLines(extractedText);
        }
        if (slice == null || slice.dataRows.isEmpty()) {
            int rows = hints != null ? hints.dataRowCount() : 0;
            int cols = hints != null ? hints.expectedColumnCount() : 0;
            return TableExtractionPlan.singlePass(extractedText, rows, cols);
        }

        int batchSize = effectiveBatchSize(properties.getLlmBatchSize(), slice.columnCount);
        int singlePassMax = Math.max(0, properties.getLlmSinglePassMaxRows());
        int dataRowCount = slice.dataRows.size();

        if (singlePassMax > 0 && dataRowCount <= singlePassMax) {
            return TableExtractionPlan.singlePass(extractedText, dataRowCount, slice.columnCount);
        }

        List<String> sampleRows = slice.dataRows.stream()
                .limit(SAMPLE_ROWS)
                .map(TabularRow::text)
                .toList();
        List<TableRowBatch> batches = buildBatches(slice.dataRows, batchSize, slice.headerText);

        return new TableExtractionPlan(
                false,
                slice.headerText,
                sampleRows,
                batches,
                dataRowCount,
                slice.columnCount,
                extractedText);
    }

    private static List<TableRowBatch> buildBatches(List<TabularRow> dataRows, int batchSize,
                                                    String headerText) {
        List<TableRowBatch> batches = new ArrayList<>();
        int totalBatches = (dataRows.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < dataRows.size(); i += batchSize) {
            int end = Math.min(i + batchSize, dataRows.size());
            List<TabularRow> chunk = dataRows.subList(i, end);
            StringBuilder text = new StringBuilder();
            int batchIndex = (i / batchSize) + 1;
            if (batchIndex == 1 && headerText != null && !headerText.isBlank()) {
                text.append(headerText.trim()).append(System.lineSeparator());
            }
            for (TabularRow row : chunk) {
                text.append(row.text()).append(System.lineSeparator());
            }
            batches.add(new TableRowBatch(
                    batchIndex,
                    totalBatches,
                    chunk.get(0).rowNumber(),
                    chunk.get(chunk.size() - 1).rowNumber(),
                    text.toString().trim()));
        }
        return batches;
    }

    private static TabularSlice sliceFromMarkdown(String text) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("|") || !line.endsWith("|") || isSeparatorRow(line)) {
                continue;
            }
            List<String> headers = parseMarkdownRow(line);
            if (headers.isEmpty()) {
                continue;
            }
            String headerText = line;
            List<TabularRow> dataRows = new ArrayList<>();
            int rowNum = 1;
            for (int j = i + 1; j < lines.size(); j++) {
                String row = lines.get(j).trim();
                if (!row.startsWith("|")) {
                    break;
                }
                if (isSeparatorRow(row)) {
                    continue;
                }
                dataRows.add(new TabularRow(rowNum++, row));
            }
            if (dataRows.isEmpty()) {
                return null;
            }
            return new TabularSlice(headerText, dataRows, headers.size());
        }
        return null;
    }

    private static TabularSlice sliceFromRowLines(String text) {
        List<TabularRow> allRows = new ArrayList<>();
        for (String line : text.lines().toList()) {
            Matcher matcher = ROW_LINE.matcher(line.trim());
            if (matcher.matches()) {
                allRows.add(new TabularRow(
                        Integer.parseInt(matcher.group(1)),
                        line.trim()));
            }
        }
        if (allRows.size() < 2) {
            return null;
        }
        TabularRow header = allRows.get(0);
        List<TabularRow> dataRows = allRows.subList(1, allRows.size());
        int columnCount = countTabSeparatedColumns(header.text());
        return new TabularSlice(header.text(), dataRows, columnCount);
    }

    private static int countTabSeparatedColumns(String rowLine) {
        Matcher matcher = ROW_LINE.matcher(rowLine.trim());
        if (!matcher.matches()) {
            return 0;
        }
        String cells = matcher.group(2);
        if (cells == null || cells.isBlank()) {
            return 0;
        }
        return cells.split("\t", -1).length;
    }

    private static List<String> parseMarkdownRow(String line) {
        String trimmed = line.substring(1, line.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private static boolean isSeparatorRow(String line) {
        return line.matches("\\|\\s*[-: ]+\\s*(\\|\\s*[-: ]+\\s*)+\\|");
    }

    /** Narrow batches for wide tables so LLM JSON stays within output token limits. */
    static int effectiveBatchSize(int configured, int columnCount) {
        if (columnCount <= 0) {
            return Math.max(1, configured);
        }
        int byWidth = Math.max(1, 7000 / (columnCount * 20));
        return Math.max(1, Math.min(configured, byWidth));
    }

    private record TabularRow(int rowNumber, String text) {
    }

    private record TabularSlice(String headerText, List<TabularRow> dataRows, int columnCount) {
    }
}
