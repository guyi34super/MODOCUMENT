package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionMode;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.dto.TableRowBatch;
import com.modocument.application.port.PromptStrategy;
import com.modocument.domain.entity.TemplateFields;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Table-extraction prompts tuned for smaller local models (e.g. Mistral via Ollama):
 * short numbered steps, authoritative header anchoring from pre-scan hints, and a
 * concrete JSON example built from the document metadata.
 */
@Component
public class TableExtractionPrompt implements PromptStrategy {

    /**
     * The prompt contract version, part of the AI result cache key (ws28):
     * a cached reply is only reused for the prompt version that produced it.
     * <ul>
     *   <li>1 — row batches answered {@code {"rows":[{"Col":"val",…}]}}
     *       (every column name repeated on every row);</li>
     *   <li>2 (ws28) — row batches answer {@code {"columns":[…],"rows":[[…]]}}:
     *       the names once, each row a positional array — fewer output
     *       tokens. The parser still accepts the version-1 shape.</li>
     * </ul>
     */
    public static final String VERSION = "2";

    @Override
    public String getType() {
        return "TableExtraction";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof TableExtractionPromptInput tableInput)) {
            throw new IllegalArgumentException("Invalid input type for TableExtractionPrompt");
        }

        return switch (tableInput.getMode()) {
            case SCHEMA -> buildSchemaPrompt(tableInput);
            case ROW_BATCH -> buildRowBatchPrompt(tableInput);
            case FULL -> buildFullPrompt(tableInput);
            case EXTRACT_AND_MAP -> buildExtractAndMapPrompt(tableInput);
        };
    }

    private static String buildExtractAndMapPrompt(TableExtractionPromptInput tableInput) {
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        TableExtractionHints hints = tableInput.getHints();
        List<TemplateFields> templateFields = tableInput.getTemplateFields();
        List<String> headers = authoritativeHeaders(hints);

        prompt.append("TASK: Extract the main data table and map columns to template fields.").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. Find the header row in the document (ignore titles, notes, page headers).").append(nl);
        if (!headers.isEmpty()) {
            prompt.append("2. Use the AUTHORITATIVE COLUMN NAMES below — same spelling, same order.").append(nl);
            prompt.append("3. Extract every data row; keep column order.").append(nl);
            prompt.append("4. Map each column to the best template field (or null).").append(nl);
        } else {
            prompt.append("2. Copy header labels exactly into the columns array.").append(nl);
            prompt.append("3. Extract every data row; keep column order.").append(nl);
            prompt.append("4. Map each column to the best template field (or null).").append(nl);
        }
        prompt.append("5. Return ONE JSON object only (no markdown, no explanation).").append(nl);

        appendAuthoritativeHeaders(prompt, nl, hints);
        appendDynamicExample(prompt, nl, headers, templateFields, true);
        appendOutputContract(prompt, nl,
                "{\"columns\":[\"Col1\",\"Col2\"],"
                        + "\"mapping\":{\"Col1\":\"TemplateField1\",\"Col2\":null},"
                        + "\"rows\":[{\"Col1\":\"val\",\"Col2\":\"val\"}]}");
        appendMetadata(prompt, nl, hints, null, tableInput.getBatch());
        appendMappingRules(prompt, nl, templateFields, headers);
        appendCommonRules(prompt, nl, hints, headers);
        appendFormatRules(prompt, nl, hints);
        prompt.append(nl).append("DOCUMENT:").append(nl);
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }

    private static String buildFullPrompt(TableExtractionPromptInput tableInput) {
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        TableExtractionHints hints = tableInput.getHints();
        List<String> headers = authoritativeHeaders(hints);

        prompt.append("TASK: Extract the main data table (column names + all rows).").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. Locate the real header row.").append(nl);
        if (!headers.isEmpty()) {
            prompt.append("2. Set columns to the AUTHORITATIVE names below (exact spelling and order).").append(nl);
        } else {
            prompt.append("2. Copy header labels exactly into columns.").append(nl);
        }
        prompt.append("3. Extract every data row as string values.").append(nl);
        prompt.append("4. Return ONE JSON object only.").append(nl);

        appendAuthoritativeHeaders(prompt, nl, hints);
        appendDynamicExample(prompt, nl, headers, List.of(), false);
        appendOutputContract(prompt, nl, "{\"columns\":[\"Col1\",\"Col2\"],\"rows\":[{\"Col1\":\"val\"}]}");
        appendMetadata(prompt, nl, hints, null, null);
        appendCommonRules(prompt, nl, hints, headers);
        appendFormatRules(prompt, nl, hints);
        prompt.append(nl).append("DOCUMENT:").append(nl);
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }

    private static String buildSchemaPrompt(TableExtractionPromptInput tableInput) {
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        TableExtractionHints hints = tableInput.getHints();
        List<String> headers = authoritativeHeaders(hints);

        prompt.append("TASK: Return ONLY the column names for the main table.").append(nl);
        if (!headers.isEmpty()) {
            prompt.append("Use the AUTHORITATIVE COLUMN NAMES below — return them in the same order.").append(nl);
        } else {
            prompt.append("Copy the header row labels exactly. Do not rename or merge columns.").append(nl);
        }
        appendAuthoritativeHeaders(prompt, nl, hints);
        appendOutputContract(prompt, nl, "{\"columns\":[\"Col1\",\"Col2\"]}");
        appendMetadata(prompt, nl, hints, null, null);
        if (hints != null && hints.expectedColumnCount() > 0 && headers.isEmpty()) {
            prompt.append("- columns MUST have exactly ").append(hints.expectedColumnCount())
                    .append(" entries.").append(nl);
        }
        appendFormatRules(prompt, nl, hints);
        prompt.append(nl).append("HEADER AND SAMPLE ROWS:").append(nl);
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }

    private static String buildRowBatchPrompt(TableExtractionPromptInput tableInput) {
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        TableExtractionHints hints = tableInput.getHints();
        List<String> columns = tableInput.getFixedColumns();
        TableRowBatch batch = tableInput.getBatch();

        prompt.append("TASK: Extract data rows for this batch using the FIXED column schema.").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. Read only the rows in this batch.").append(nl);
        prompt.append("2. Put the fixed column names ONCE in \"columns\" (character-for-character, same order).")
                .append(nl);
        prompt.append("3. Write each row as an ARRAY of cell values in that column order — one value per "
                + "column, \"\" for an empty cell; never repeat the column names in a row.").append(nl);
        prompt.append("4. Return ONE JSON object: {\"columns\":[...],\"rows\":[[...],[...]]} only.").append(nl);

        appendOutputContract(prompt, nl,
                "{\"columns\":[\"Col1\",\"Col2\"],\"rows\":[[\"val\",\"val\"],[\"val\",\"\"]]}");
        appendMetadata(prompt, nl, hints, columns, batch);
        if (columns != null && !columns.isEmpty()) {
            prompt.append("FIXED COLUMN KEYS (use exactly):").append(nl);
            for (int i = 0; i < columns.size(); i++) {
                prompt.append(i + 1).append(". \"").append(columns.get(i)).append("\"").append(nl);
            }
            prompt.append(nl);
        }
        appendFormatRules(prompt, nl, hints);
        prompt.append(nl).append("BATCH ROWS:").append(nl);
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }

    private static List<String> authoritativeHeaders(TableExtractionHints hints) {
        if (hints == null || hints.candidateHeaderCells() == null) {
            return List.of();
        }
        List<String> headers = new ArrayList<>();
        for (String cell : hints.candidateHeaderCells()) {
            if (cell != null && !cell.isBlank()) {
                headers.add(cell.trim());
            }
        }
        return headers;
    }

    private static void appendAuthoritativeHeaders(StringBuilder prompt, String nl,
                                                   TableExtractionHints hints) {
        List<String> headers = authoritativeHeaders(hints);
        if (headers.isEmpty()) {
            return;
        }
        prompt.append(nl).append("AUTHORITATIVE COLUMN NAMES (use exactly, in this order):").append(nl);
        for (int i = 0; i < headers.size(); i++) {
            prompt.append(i + 1).append(". \"").append(headers.get(i)).append("\"").append(nl);
        }
        prompt.append("Do NOT invent, rename, merge, or drop columns.").append(nl);
    }

    private static void appendDynamicExample(StringBuilder prompt, String nl,
                                             List<String> headers,
                                             List<TemplateFields> templateFields,
                                             boolean includeMapping) {
        if (headers.isEmpty()) {
            return;
        }
        String col1 = headers.get(0);
        String col2 = headers.size() > 1 ? headers.get(1) : col1;
        String field1 = suggestField(col1, templateFields);
        prompt.append(nl).append("EXAMPLE (structure only — use real cell values from the document):").append(nl);
        if (includeMapping) {
            prompt.append("{\"columns\":[\"").append(col1).append("\",\"")
                    .append(col2).append("\"],");
            prompt.append("\"mapping\":{\"").append(col1).append("\":")
                    .append(field1 == null ? "null" : "\"" + field1 + "\"")
                    .append(",\"").append(col2).append("\":null},");
            prompt.append("\"rows\":[{").append("\"").append(col1).append("\":\"...\",\"")
                    .append(col2).append("\":\"...\"}]}").append(nl);
        } else {
            prompt.append("{\"columns\":[\"").append(col1).append("\",\"")
                    .append(col2).append("\"],");
            prompt.append("\"rows\":[{").append("\"").append(col1).append("\":\"...\",\"")
                    .append(col2).append("\":\"...\"}]}").append(nl);
        }
    }

    private static String suggestField(String column, List<TemplateFields> templateFields) {
        if (templateFields == null || templateFields.isEmpty() || column == null) {
            return null;
        }
        String normalized = normalize(column);
        for (TemplateFields field : templateFields) {
            if (field.getFieldName() == null) {
                continue;
            }
            String fieldNorm = normalize(field.getFieldName());
            if (normalized.equals(fieldNorm) || normalized.contains(fieldNorm)
                    || fieldNorm.contains(normalized)) {
                return field.getFieldName();
            }
        }
        return templateFields.get(0).getFieldName();
    }

    private static String normalize(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static void appendOutputContract(StringBuilder prompt, String nl, String schema) {
        prompt.append(nl);
        prompt.append("JSON RULES: double-quoted keys and strings; no trailing commas; ")
                .append("close every { with } and every [ with ].").append(nl);
        prompt.append("Schema: ").append(schema).append(nl);
    }

    private static void appendMetadata(StringBuilder prompt, String nl, TableExtractionHints hints,
                                       List<String> fixedColumns, TableRowBatch batch) {
        prompt.append(nl).append("SOURCE METADATA:").append(nl);
        if (hints != null) {
            if (hints.sourceFormat() != null && !hints.sourceFormat().isBlank()) {
                prompt.append("- Format: ").append(hints.sourceFormat()).append(nl);
            }
            prompt.append("- Extracted via: ").append(hints.extractionSource()).append(nl);
            if (hints.expectedColumnCount() > 0) {
                prompt.append("- Expected columns: ").append(hints.expectedColumnCount()).append(nl);
            }
            if (hints.dataRowCount() > 0) {
                prompt.append("- Approximate data rows: ").append(hints.dataRowCount()).append(nl);
            }
        }
        if (fixedColumns != null && !fixedColumns.isEmpty()) {
            prompt.append("- Fixed column schema: ")
                    .append(fixedColumns.stream()
                            .map(c -> "\"" + c + "\"")
                            .collect(Collectors.joining(", ", "[", "]")))
                    .append(nl);
        }
        if (batch != null) {
            prompt.append("- Batch: ").append(batch.batchIndex())
                    .append(" of ").append(batch.totalBatches())
                    .append(" (rows ").append(batch.startRow())
                    .append("–").append(batch.endRow()).append(")").append(nl);
        }
        prompt.append(nl);
    }

    private static void appendMappingRules(StringBuilder prompt, String nl,
                                           List<TemplateFields> templateFields,
                                           List<String> headers) {
        prompt.append("MAPPING RULES:").append(nl);
        prompt.append("- mapping keys MUST match columns entries exactly.").append(nl);
        prompt.append("- mapping values MUST be template field names below, or null.").append(nl);
        prompt.append("- Each template field at most once.").append(nl);
        prompt.append("- Match by meaning: e.g. \"Emp ID\"→ID, \"Full Name\"→Name, \"Dept\"→Department.").append(nl);
        if (!headers.isEmpty() && templateFields != null && !templateFields.isEmpty()) {
            prompt.append("- Prefer mapping headers above to semantically similar template fields.").append(nl);
        }
        prompt.append(nl);
        prompt.append("TEMPLATE FIELDS (name | type):").append(nl);
        if (templateFields == null || templateFields.isEmpty()) {
            prompt.append("(none)").append(nl);
            return;
        }
        int index = 1;
        for (TemplateFields field : templateFields) {
            prompt.append(index++).append(". \"").append(field.getFieldName()).append("\" | ")
                    .append(dataTypeOf(field)).append(nl);
        }
        prompt.append(nl);
    }

    private static void appendCommonRules(StringBuilder prompt, String nl,
                                          TableExtractionHints hints, List<String> headers) {
        prompt.append("DATA RULES:").append(nl);
        prompt.append("- Skip title rows, blank rows, footnotes, and page headers/footers.").append(nl);
        prompt.append("- Merged cells: repeat the value for every row/column spanned.").append(nl);
        prompt.append("- All cell values are strings; use \"\" for empty cells.").append(nl);
        prompt.append("- Do not invent rows or columns not present in the document.").append(nl);
        if (hints != null && hints.expectedColumnCount() > 0 && headers.isEmpty()) {
            prompt.append("- columns MUST contain exactly ").append(hints.expectedColumnCount())
                    .append(" entries.").append(nl);
        }
        if (!headers.isEmpty()) {
            prompt.append("- Every row object MUST include all authoritative column keys.").append(nl);
        }
    }

    private static void appendFormatRules(StringBuilder prompt, String nl, TableExtractionHints hints) {
        if (hints == null) {
            return;
        }
        String format = hints.sourceFormat() == null ? "" : hints.sourceFormat().toLowerCase();
        prompt.append("FORMAT HINTS:").append(nl);
        if ("docling".equalsIgnoreCase(hints.extractionSource())) {
            prompt.append("- Pipe-separated markdown table: preserve every header cell from the | row.").append(nl);
        }
        if (format.endsWith(".xlsx") || format.endsWith(".xls") || format.endsWith(".xlsm")) {
            prompt.append("- Lines like \"Row N: col\\tcol\" — Row N is context only, NOT a column.").append(nl);
            prompt.append("- Tab-separated cells after the colon belong to the table columns.").append(nl);
        }
        if (format.endsWith(".pdf")) {
            prompt.append("- Tables may continue across pages; ignore repeated headers/watermarks.").append(nl);
        }
        if (format.endsWith(".csv")) {
            prompt.append("- First delimited row is the header; preserve every column.").append(nl);
        }
    }

    private static String dataTypeOf(TemplateFields field) {
        if (field.getTemplateDataType() == null || field.getTemplateDataType().getDataType() == null) {
            return "Text";
        }
        return field.getTemplateDataType().getDataType();
    }

    /** Appended on retry when the model returned too few columns. */
    public static String correctiveSuffix(int returned, int expected) {
        return System.lineSeparator() + System.lineSeparator()
                + "CORRECTION: You returned " + returned + " columns but the source has "
                + expected + ". Return all " + expected + " columns with exact header names "
                + "from the document header row.";
    }

    /** Appended on retry when a row batch returned too few rows. */
    public static String batchCorrectiveSuffix(int returned, int expected) {
        return System.lineSeparator() + System.lineSeparator()
                + "CORRECTION: You returned " + returned + " rows but this batch contains "
                + expected + ". Return every row in this batch.";
    }

    /** Appended on retry when the model returned invalid JSON. */
    public static String jsonParseCorrectiveSuffix(String parseError) {
        String detail = parseError == null || parseError.isBlank() ? "invalid JSON" : parseError;
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        return System.lineSeparator() + System.lineSeparator()
                + "CORRECTION: Previous reply was invalid JSON (" + detail + "). "
                + "Return ONLY one JSON object. Use double quotes. No markdown.";
    }
}
