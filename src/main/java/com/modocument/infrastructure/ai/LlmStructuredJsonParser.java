package com.modocument.infrastructure.ai;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.modocument.application.dto.TableExtractAndMapResult;
import com.modocument.application.dto.TableRowsResult;
import com.modocument.application.dto.TableRowsResultDeserializer;
import com.modocument.application.dto.TableSchemaResult;
import com.modocument.application.dto.TableSchemaResultDeserializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses flexible LLM JSON text into table extraction DTOs (bypasses Spring AI entity()). */
public final class LlmStructuredJsonParser {

    private static final Pattern FENCED_JSON =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // Lenient on purpose: LLMs routinely emit off-spec JSON (leading-decimal
    // numbers like `.5`, single quotes, trailing commas, comments). Enabling
    // these read features lets Jackson accept them instead of throwing a raw
    // parse error that surfaces to the user as a 500.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
            .enable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_MISSING_VALUES)
            .build()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .registerModule(new SimpleModule()
                    .addDeserializer(TableRowsResult.class, new TableRowsResultDeserializer())
                    .addDeserializer(TableSchemaResult.class, new TableSchemaResultDeserializer()));

    private static final Pattern JACKSON_LOCATION =
            Pattern.compile("line:\\s*(\\d+),\\s*column:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Enough passes for each independent repair (quotes, commas, ellipsis, decimals, brackets, close) to fire once. */
    private static final int MAX_REPAIR_ATTEMPTS = 8;

    private LlmStructuredJsonParser() {
    }

    /**
     * Distinctive sentences from Spring AI's {@code BeanOutputConverter.getFormat()}
     * boilerplate (appended to every structured prompt). A correct model never
     * repeats these in its answer; weak/local models with a small context window
     * truncate the real payload and parrot the trailing format instructions back
     * as "data", which would otherwise be written into the table verbatim.
     */
    private static final String[] INSTRUCTION_ECHO_SIGNATURES = {
            "rfc8259 compliant json response",
            "here is the json schema instance your output must adhere to",
            "following this format without deviation",
            "remove the ```json markdown",
            "do not include markdown code blocks in your response"
    };

    /** True when the raw model reply repeats our own format instructions instead of returning a real result. */
    public static boolean looksLikeInstructionEcho(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        for (String signature : INSTRUCTION_ECHO_SIGNATURES) {
            if (lower.contains(signature)) {
                return true;
            }
        }
        return false;
    }

    public static TableRowsResult parseRows(String raw) {
        return parseRows(raw, null);
    }

    public static TableRowsResult parseRows(String raw, List<String> columns) {
        String json = repairLeadingDecimalNumbers(extractJsonPayload(raw));
        RuntimeException lastError = null;
        String current = json;
        for (int attempt = 0; attempt < MAX_REPAIR_ATTEMPTS; attempt++) {
            try {
                return readRows(current, columns);
            } catch (RuntimeException e) {
                lastError = e;
                String next = tryRepairStep(current, e);
                if (!next.equals(current)) {
                    current = next;
                    continue;
                }
                break;
            }
        }
        throw lastError;
    }

    private static TableRowsResult readRows(String json, List<String> columns) {
        if (columns != null && !columns.isEmpty()) {
            try {
                List<Map<String, String>> coerced = coerceRows(MAPPER.readTree(json), columns);
                if (!coerced.isEmpty()) {
                    TableRowsResult result = new TableRowsResult();
                    result.setRows(coerced);
                    return result;
                }
            } catch (Exception ignored) {
                // fall through to generic parsers
            }
        }
        try {
            return MAPPER.readValue(json, TableRowsResult.class);
        } catch (Exception first) {
            try {
                List<Map<String, String>> rows = MAPPER.readValue(json,
                        new TypeReference<>() {
                        });
                TableRowsResult result = new TableRowsResult();
                result.setRows(rows);
                return result;
            } catch (Exception second) {
                throw new IllegalStateException(
                        "Could not parse LLM row batch JSON: " + summarize(first), first);
            }
        }
    }

    static List<Map<String, String>> coerceRows(JsonNode root, List<String> columns) {
        if (root == null || root.isNull() || columns == null || columns.isEmpty()) {
            return List.of();
        }
        JsonNode rowsNode;
        if (root.isArray()) {
            rowsNode = root;
        } else if (root.isObject() && root.has("rows") && root.get("rows").isArray()) {
            rowsNode = root.get("rows");
        } else {
            return List.of();
        }
        if (rowsNode.isEmpty()) {
            return List.of();
        }
        JsonNode first = rowsNode.get(0);
        if (first.isValueNode()) {
            return List.of(coerceScalarRow(rowsNode, columns));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode rowNode : rowsNode) {
            Map<String, String> row = coerceRow(rowNode, columns);
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, String> coerceScalarRow(JsonNode values, List<String> columns) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), i < values.size() ? values.get(i).asText("") : "");
        }
        return row;
    }

    private static Map<String, String> coerceRow(JsonNode rowNode, List<String> columns) {
        if (rowNode == null || rowNode.isNull()) {
            return Map.of();
        }
        if (rowNode.isObject()) {
            Map<String, String> row = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = rowNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                row.put(field.getKey(), field.getValue().asText(""));
            }
            return row;
        }
        if (rowNode.isArray()) {
            return coerceScalarRow(rowNode, columns);
        }
        return Map.of();
    }

    public static TableSchemaResult parseSchema(String raw) {
        String json = extractJsonPayload(raw);
        try {
            return MAPPER.readValue(json, TableSchemaResult.class);
        } catch (Exception first) {
            try {
                List<String> columns = MAPPER.readValue(json, new TypeReference<>() {
                });
                TableSchemaResult result = new TableSchemaResult();
                result.setColumns(columns);
                return result;
            } catch (Exception second) {
                throw new IllegalStateException(
                        "Could not parse LLM schema JSON: " + summarize(first), first);
            }
        }
    }

    /**
     * Parses a raw LLM reply into an arbitrary target type, applying the same
     * payload extraction + repair pipeline the table parsers use. This is the
     * lenient path for structured-output calls (chat edit, auto-map, etc.) that
     * would otherwise hit Spring AI's strict converter.
     */
    public static <T> T parseInto(String raw, Type type) {
        // Leading-decimal numbers (`.5`) parse under the lenient reader but keep
        // their raw ".5" text; normalize up front so string cells read "0.5".
        String extracted = extractJsonPayload(raw);
        String json = repairLeadingDecimalNumbers(extracted);
        JavaType javaType = MAPPER.getTypeFactory().constructType(type);
        RuntimeException lastError = null;
        String current = json;
        for (int attempt = 0; attempt < MAX_REPAIR_ATTEMPTS; attempt++) {
            try {
                return MAPPER.readValue(current, javaType);
            } catch (RuntimeException e) {
                lastError = e;
                String next = tryRepairStep(current, e);
                if (!next.equals(current)) {
                    current = next;
                    continue;
                }
                break;
            } catch (Exception e) {
                RuntimeException wrapped = new IllegalStateException(
                        "Could not parse LLM JSON: " + summarize(e), e);
                lastError = wrapped;
                String next = tryRepairStep(current, wrapped);
                if (!next.equals(current)) {
                    current = next;
                    continue;
                }
                break;
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("Could not parse LLM JSON.");
    }

    public static TableExtractAndMapResult parseExtractAndMap(String raw, List<String> templateFieldNames) {
        String json = repairLeadingDecimalNumbers(extractJsonPayload(raw));
        RuntimeException lastError = null;
        String current = json;
        for (int attempt = 0; attempt < MAX_REPAIR_ATTEMPTS; attempt++) {
            try {
                return readExtractAndMap(current, templateFieldNames);
            } catch (RuntimeException e) {
                lastError = e;
                String next = tryRepairStep(current, e);
                if (!next.equals(current)) {
                    current = next;
                    continue;
                }
                break;
            }
        }
        throw lastError;
    }

    private static TableExtractAndMapResult readExtractAndMap(String json, List<String> templateFieldNames) {
        try {
            JsonNode root = MAPPER.readTree(json);
            TableExtractAndMapResult result = new TableExtractAndMapResult();

            List<String> columns = parseColumnsNode(root);
            if (columns.isEmpty()) {
                throw new IllegalStateException("Combined extraction JSON has no columns.");
            }
            result.setColumns(columns);

            result.setMapping(sanitizeMapping(root.get("mapping"), columns, templateFieldNames));

            List<Map<String, String>> rows = coerceRows(root, columns);
            if (rows.isEmpty() && root.has("rows")) {
                TableRowsResult parsedRows = parseRows(json, columns);
                rows = parsedRows.getRows() == null ? List.of() : parsedRows.getRows();
            }
            result.setRows(rows);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not parse LLM extract-and-map JSON: " + summarize(e), e);
        }
    }

    private static final Pattern ELLIPSIS_ELEMENT =
            Pattern.compile("(?m)^[ \\t]*(?:\\.\\.\\.|\\u2026)[ \\t]*,?[ \\t]*\\r?\\n?");

    private static final Pattern LEADING_DECIMAL =
            Pattern.compile("([\\[{,:]\\s*)\\.(\\d)");

    /** Fixes trailing commas and similar common LLM JSON mistakes. */
    static String repairCommonLlmJsonIssues(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        return json.replaceAll(",\\s*]", "]").replaceAll(",\\s*}", "}");
    }

    /** Drops standalone `...` / `…` placeholder elements (LLM "and so on") and the comma they leave behind. */
    static String stripEllipsisPlaceholders(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        String stripped = ELLIPSIS_ELEMENT.matcher(json).replaceAll("");
        // A removed element can leave a dangling comma before the closing bracket.
        return stripped.replaceAll(",\\s*]", "]").replaceAll(",\\s*}", "}");
    }

    /** Turns unquoted leading-decimal numbers (`: .5`) into valid JSON (`: 0.5`). */
    static String repairLeadingDecimalNumbers(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        return LEADING_DECIMAL.matcher(json).replaceAll("$10.$2");
    }

    /** Applies quote, trailing-comma, ellipsis, decimal, bracket, and unclosed-json repairs; returns unchanged if none apply. */
    private static String tryRepairStep(String current, RuntimeException e) {
        // Quote normalization runs first: single-quoted JSON (and values with
        // stray/inner quotes) is the most common failure and it makes the later
        // bracket-counting repairs reliable. Safe/idempotent on valid JSON.
        String quoteFix = normalizeQuotes(current);
        if (!quoteFix.equals(current)) {
            return quoteFix;
        }
        String repaired = repairCommonLlmJsonIssues(current);
        if (!repaired.equals(current)) {
            return repaired;
        }
        String ellipsisFix = stripEllipsisPlaceholders(current);
        if (!ellipsisFix.equals(current)) {
            return ellipsisFix;
        }
        String decimalFix = repairLeadingDecimalNumbers(current);
        if (!decimalFix.equals(current)) {
            return decimalFix;
        }
        String bracketFix = repairMismatchedCloseBracket(current, e.getMessage());
        if (!bracketFix.equals(current)) {
            return bracketFix;
        }
        String closed = repairUnclosedJson(current);
        if (!closed.equals(current)) {
            return closed;
        }
        return current;
    }

    /**
     * Rewrites single-quoted (and mixed-quote) JSON strings into strictly
     * double-quoted strings, tolerating apostrophes inside values
     * ({@code 'O'Brien'} -> {@code "O'Brien"}) and escaping literal double
     * quotes ({@code 'he said "hi"'} -> {@code "he said \"hi\""}). LLMs
     * frequently emit single-quoted JSON; {@code ALLOW_SINGLE_QUOTES} handles
     * the trivial cases but throws the moment a value contains an apostrophe,
     * so we normalize the quoting here.
     *
     * <p>Heuristic: a {@code '} or {@code "} opens a string; the matching
     * closer is the next occurrence of that same quote char that is followed
     * (ignoring whitespace) by a structural token ({@code : , } ]}) or the end
     * of input. Any occurrence of the opener not followed by structure is
     * treated as a literal character inside the string. This is safe and a
     * no-op on already-valid double-quoted JSON (inner quotes are escaped, so
     * they never reach the closer check).
     */
    static String normalizeQuotes(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        int n = json.length();
        StringBuilder out = new StringBuilder(n + 16);
        int i = 0;
        while (i < n) {
            char c = json.charAt(i);
            if (c != '"' && c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            char opener = c;
            out.append('"');
            i++;
            boolean closed = false;
            while (i < n) {
                char d = json.charAt(i);
                if (d == '\\') {
                    if (i + 1 < n) {
                        char next = json.charAt(i + 1);
                        if (next == '\'') {
                            // \' is illegal in JSON strings; emit a bare apostrophe.
                            out.append('\'');
                        } else if (next == '"') {
                            out.append('\\').append('"');
                        } else {
                            out.append('\\').append(next);
                        }
                        i += 2;
                    } else {
                        out.append('\\');
                        i++;
                    }
                    continue;
                }
                if (d == opener) {
                    if (isStringCloser(json, i + 1)) {
                        out.append('"');
                        i++;
                        closed = true;
                        break;
                    }
                    // Literal occurrence of the opener inside the string value.
                    if (d == '"') {
                        out.append('\\').append('"');
                    } else {
                        out.append('\'');
                    }
                    i++;
                    continue;
                }
                if (d == '"') {
                    // Literal double quote inside a single-quoted string.
                    out.append('\\').append('"');
                    i++;
                    continue;
                }
                out.append(d);
                i++;
            }
            if (!closed) {
                // Unterminated string at end of input: close it so the result
                // stays valid for the follow-up bracket/close repairs.
                out.append('"');
            }
        }
        return out.toString();
    }

    /** True when the next non-whitespace char at/after {@code idx} ends a JSON string (structural token or EOF). */
    private static boolean isStringCloser(String json, int idx) {
        int j = idx;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length()) {
            return true;
        }
        char c = json.charAt(j);
        return c == ':' || c == ',' || c == '}' || c == ']';
    }

    /** Appends missing } / ] when the payload ends before all scopes are closed. */
    static String repairUnclosedJson(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        char[] stack = new char[json.length()];
        int stackSize = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                stack[stackSize++] = c;
            } else if (c == '}' && stackSize > 0 && stack[stackSize - 1] == '{') {
                stackSize--;
            } else if (c == ']' && stackSize > 0 && stack[stackSize - 1] == '[') {
                stackSize--;
            }
        }
        if (stackSize == 0) {
            return json;
        }
        StringBuilder repaired = new StringBuilder(json);
        for (int i = stackSize - 1; i >= 0; i--) {
            repaired.append(stack[i] == '{' ? '}' : ']');
        }
        return repaired.toString();
    }

    /** Swaps mismatched close brackets at Jackson's reported error location. */
    static String repairMismatchedCloseBracket(String json, String errorMessage) {
        if (json == null || errorMessage == null) {
            return json == null ? "" : json;
        }
        int line = parseJacksonLine(errorMessage);
        int column = parseJacksonColumn(errorMessage);
        if (line <= 0 || column <= 0) {
            return json;
        }
        int index = indexOfLineColumn(json, line, column);
        if (index < 0 || index >= json.length()) {
            return json;
        }
        char c = json.charAt(index);
        if (errorMessage.contains("expected '}'") && c == ']') {
            return json.substring(0, index) + '}' + json.substring(index + 1);
        }
        if (errorMessage.contains("expected ']'") && c == '}') {
            return json.substring(0, index) + ']' + json.substring(index + 1);
        }
        return json;
    }

    static int parseJacksonLine(String errorMessage) {
        Matcher m = JACKSON_LOCATION.matcher(errorMessage);
        int line = -1;
        while (m.find()) {
            line = Integer.parseInt(m.group(1));
        }
        return line;
    }

    static int parseJacksonColumn(String errorMessage) {
        Matcher m = JACKSON_LOCATION.matcher(errorMessage);
        int column = -1;
        while (m.find()) {
            column = Integer.parseInt(m.group(2));
        }
        return column;
    }

    static int indexOfLineColumn(String text, int line, int column) {
        if (text == null || line <= 0 || column <= 0) {
            return -1;
        }
        int currentLine = 1;
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (currentLine == line) {
                int colIndex = lineStart + column - 1;
                return colIndex < text.length() ? colIndex : text.length();
            }
            if (text.charAt(i) == '\n') {
                currentLine++;
                lineStart = i + 1;
            }
        }
        if (currentLine == line) {
            int colIndex = lineStart + column - 1;
            return colIndex < text.length() ? colIndex : text.length();
        }
        return -1;
    }

    private static List<String> parseColumnsNode(JsonNode root) {
        if (root == null || !root.isObject() || !root.has("columns") || !root.get("columns").isArray()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (JsonNode column : root.get("columns")) {
            columns.add(column.asText(""));
        }
        return columns;
    }

    static Map<String, String> sanitizeMapping(JsonNode mappingNode, List<String> columns,
                                               List<String> templateFieldNames) {
        if (mappingNode == null || mappingNode.isNull() || !mappingNode.isObject()) {
            return Map.of();
        }
        Set<String> allowedFields = templateFieldNames == null
                ? Set.of()
                : new HashSet<>(templateFieldNames);
        Set<String> usedFields = new HashSet<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String column : columns) {
            JsonNode target = mappingNode.get(column);
            if (target == null || target.isNull()) {
                mapping.put(column, null);
                continue;
            }
            String fieldName = target.asText("");
            if (fieldName.isBlank() || !allowedFields.contains(fieldName)
                    || usedFields.contains(fieldName)) {
                mapping.put(column, null);
                continue;
            }
            mapping.put(column, fieldName);
            usedFields.add(fieldName);
        }
        return mapping;
    }

    static String extractJsonPayload(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(trimmed);
        if (fenced.find()) {
            trimmed = fenced.group(1).trim();
        }
        int startObj = trimmed.indexOf('{');
        int startArr = trimmed.indexOf('[');
        int start;
        if (startObj < 0) {
            start = startArr;
        } else if (startArr < 0) {
            start = startObj;
        } else {
            start = Math.min(startObj, startArr);
        }
        if (start < 0) {
            return trimmed;
        }
        return extractBalancedJson(trimmed, start);
    }

    /** Finds the outermost balanced JSON value, respecting quoted strings. */
    static String extractBalancedJson(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }

    private static String summarize(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
