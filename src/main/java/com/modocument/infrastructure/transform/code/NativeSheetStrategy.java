package com.modocument.infrastructure.transform.code;

import com.modocument.application.dto.TableData;
import com.modocument.infrastructure.file.TabularNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spreadsheet cells as {@link TabularNormalizer} reads them (primary sheet,
 * detected header row, merged cells filled, formulas evaluated; CSV with the
 * comma), then the usual clean-up of totals and footers.
 */
final class NativeSheetStrategy implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(NativeSheetStrategy.class);

    private final TabularNormalizer tabularNormalizer;

    NativeSheetStrategy(TabularNormalizer tabularNormalizer) {
        this.tabularNormalizer = tabularNormalizer;
    }

    @Override
    public CodeStrategy name() {
        return CodeStrategy.NATIVE_SHEET;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        if (!context.hasReadableFile() || !tabularNormalizer.supports(context.fileType())) {
            return List.of();
        }
        TableData data;
        try {
            data = tabularNormalizer.normalize(context.file());
        } catch (RuntimeException e) {
            log.debug("Native sheet read failed: {}", e.getMessage());
            return List.of();
        }
        if (data == null || data.getColumns() == null || data.getColumns().isEmpty()
                || data.getRows() == null || data.getRows().isEmpty()) {
            return List.of();
        }
        List<String> headers = TextCells.uniqueHeaders(data.getColumns());
        List<List<String>> rows = new ArrayList<>(data.getRows().size());
        for (Map<String, String> row : data.getRows()) {
            List<String> cells = new ArrayList<>(headers.size());
            for (String column : data.getColumns()) {
                cells.add(row.getOrDefault(column, ""));
            }
            rows.add(cells);
        }
        List<String> notes = new ArrayList<>();
        notes.add("read with the native " + context.fileType() + " reader");
        TableCleaner.Table table = TableCleaner.clean(headers, rows, notes);
        if (table.rows().isEmpty()) {
            return List.of();
        }
        return List.of(new Candidate(CodeStrategy.NATIVE_SHEET, table.headers(), table.rows(),
                false, 1.0, notes));
    }
}
