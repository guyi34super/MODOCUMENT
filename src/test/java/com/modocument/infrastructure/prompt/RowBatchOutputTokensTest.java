package com.modocument.infrastructure.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.infrastructure.ai.LlmStructuredJsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws28 measurement: the output tokens prompt v2 saves on the golden
 * extraction corpus. Every corpus case's golden table is written the way a
 * row batch answers — v1 {@code {"rows":[{"Col":"val",…}]}} versus v2
 * {@code {"columns":[…],"rows":[[…]]}} — and costed with the usual
 * characters/4 token estimate (no tokenizer ships with the app). The v2
 * reply must also parse back to exactly the golden rows.
 */
class RowBatchOutputTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXPECTED = Path.of("src", "test", "resources", "extraction-corpus", "expected");

    static long tokens(String text) {
        return (text.length() + 3) / 4;
    }

    @Test
    void theCompactRowShapeSavesOutputTokensOnTheGoldenCorpus() throws IOException {
        long v1Tokens = 0;
        long v2Tokens = 0;
        int cases = 0;
        int rowsTotal = 0;
        List<Path> files;
        try (Stream<Path> listing = Files.list(EXPECTED)) {
            files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            JsonNode golden = JSON.readTree(file.toFile());
            List<String> columns = new ArrayList<>();
            golden.get("fields").forEach(field -> columns.add(field.get("name").asText()));
            List<List<String>> cells = new ArrayList<>();
            golden.get("rows").forEach(row -> {
                List<String> values = new ArrayList<>();
                row.forEach(cell -> values.add(cell.isNull() ? "" : cell.asText()));
                cells.add(values);
            });
            if (columns.isEmpty() || cells.isEmpty()) {
                continue;
            }
            List<Map<String, String>> objectRows = new ArrayList<>();
            for (List<String> values : cells) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), i < values.size() ? values.get(i) : "");
                }
                objectRows.add(row);
            }
            String v1 = JSON.writeValueAsString(Map.of("rows", objectRows));
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("columns", columns);
            compact.put("rows", cells);
            String v2 = JSON.writeValueAsString(compact);

            assertThat(LlmStructuredJsonParser.parseRows(v2, columns).getRows())
                    .as(file.getFileName().toString())
                    .isEqualTo(objectRows);
            // (A one-row form costs about the same either way; the saving grows
            // with the rows of a batch — the corpus total is what is asserted.)
            v1Tokens += tokens(v1);
            v2Tokens += tokens(v2);
            cases++;
            rowsTotal += cells.size();
        }
        double saved = 100.0 * (v1Tokens - v2Tokens) / v1Tokens;
        System.out.printf("ws28 row-batch output tokens on the golden corpus (%d cases, %d rows): "
                + "v1 %d, v2 %d, saved %d (%.1f%%)%n", cases, rowsTotal, v1Tokens, v2Tokens,
                v1Tokens - v2Tokens, saved);
        assertThat(cases).isGreaterThanOrEqualTo(20);
        assertThat(saved).as("output tokens saved").isGreaterThan(25.0);
    }
}
