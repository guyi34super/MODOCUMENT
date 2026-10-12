package com.modocument.infrastructure.ai;

import com.modocument.application.dto.ChatEditPatchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmStructuredJsonParserTest {

    @Test
    void parseExtractAndMapSanitizesInvalidMappingTargets() {
        String raw = """
                {"columns":["Emp ID","Name"],
                "mapping":{"Emp ID":"EmployeeNumber","Name":"NotAField"},
                "rows":[{"Emp ID":"E001","Name":"Ada"}]}
                """;
        var result = LlmStructuredJsonParser.parseExtractAndMap(
                raw, List.of("EmployeeNumber", "FullName"));

        assertThat(result.getColumns()).containsExactly("Emp ID", "Name");
        assertThat(result.getMapping())
                .containsEntry("Emp ID", "EmployeeNumber")
                .containsEntry("Name", null);
        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void parseRowsFromBareArray() {
        String raw = "[{\"Emp ID\":\"E010\",\"Full Name\":\"Logan\"}]";
        assertThat(LlmStructuredJsonParser.parseRows(raw).getRows()).hasSize(1);
    }

    @Test
    void parseRowsFromRowsObject() {
        String raw = "{\"rows\":[{\"Name\":\"Ada\"}]}";
        assertThat(LlmStructuredJsonParser.parseRows(raw).getRows()).hasSize(1);
    }

    @Test
    void parseRowsFromFencedJson() {
        String raw = """
                Here is the JSON:
                ```json
                [{"Name":"Ada"}]
                ```
                """;
        assertThat(LlmStructuredJsonParser.parseRows(raw).getRows()).hasSize(1);
    }

    @Test
    void parseSchemaFromBareArray() {
        String raw = "[\"Emp ID\",\"Full Name\"]";
        assertThat(LlmStructuredJsonParser.parseSchema(raw).getColumns())
                .containsExactly("Emp ID", "Full Name");
    }

    @Test
    void parseRowsFromPositionalArrays() {
        List<String> columns = List.of("Emp ID", "Full Name", "Department");
        String raw = """
                [
                  ["E096", "Logan", "Engineering"],
                  ["E097", "Mia", "Sales"]
                ]
                """;
        assertThat(LlmStructuredJsonParser.parseRows(raw, columns).getRows())
                .hasSize(2)
                .containsExactly(
                        Map.of("Emp ID", "E096", "Full Name", "Logan", "Department", "Engineering"),
                        Map.of("Emp ID", "E097", "Full Name", "Mia", "Department", "Sales"));
    }

    @Test
    void parseRowsFromFlatScalarArrayAsSingleRow() {
        List<String> columns = List.of("Emp ID", "Full Name");
        String raw = "[\"E096\", \"Logan\"]";
        assertThat(LlmStructuredJsonParser.parseRows(raw, columns).getRows())
                .containsExactly(Map.of("Emp ID", "E096", "Full Name", "Logan"));
    }

    @Test
    void extractJsonPayloadRespectsBracesInsideStrings() {
        String raw = """
                {"columns":["Note"],"mapping":{"Note":null},
                "rows":[{"Note":"value with } brace inside"}]}
                """;
        assertThat(LlmStructuredJsonParser.extractJsonPayload(raw))
                .contains("value with } brace inside");
        assertThat(LlmStructuredJsonParser.parseExtractAndMap(raw, List.of()).getRows()).hasSize(1);
    }

    @Test
    void parseExtractAndMapRepairsTrailingCommas() {
        String raw = """
                {"columns":["Name"],
                "mapping":{"Name":"FullName"},
                "rows":[{"Name":"Ada"},]}
                """;
        var result = LlmStructuredJsonParser.parseExtractAndMap(raw, List.of("FullName"));
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getMapping()).containsEntry("Name", "FullName");
    }

    @Test
    void parseExtractAndMapRepairsMismatchedRowCloseBracket() {
        String raw = """
                {"columns":["Name"],
                "mapping":{"Name":"FullName"},
                "rows":[{"Name":"Ada"]}
                """;
        var result = LlmStructuredJsonParser.parseExtractAndMap(raw, List.of("FullName"));
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getMapping()).containsEntry("Name", "FullName");
    }

    @Test
    void repairMismatchedCloseBracketUsesLastJacksonLocation() {
        String json = "{\"rows\":[{\"Name\":\"Ada\"]}";
        String error = "Unexpected close marker ']': expected '}' "
                + "(for Object starting at line: 1, column: 10) at line: 1, column: 23";
        assertThat(LlmStructuredJsonParser.repairMismatchedCloseBracket(json, error))
                .isEqualTo("{\"rows\":[{\"Name\":\"Ada\"}}");
    }

    @Test
    void repairMismatchedCloseBracketSwapsBraceForBracket() {
        String json = "{\"rows\":[{\"Name\":\"Ada\"}}";
        String error = "Unexpected close marker '}': expected ']' "
                + "(for Array starting at line: 1, column: 8) at line: 1, column: 24";
        assertThat(LlmStructuredJsonParser.repairMismatchedCloseBracket(json, error))
                .isEqualTo("{\"rows\":[{\"Name\":\"Ada\"}]");
    }

    @Test
    void repairUnclosedJsonAppendsMissingRootBrace() {
        String json = "{\"columns\":[\"Name\"],\"rows\":[{\"Name\":\"Ada\"}]";
        assertThat(LlmStructuredJsonParser.repairUnclosedJson(json))
                .isEqualTo("{\"columns\":[\"Name\"],\"rows\":[{\"Name\":\"Ada\"}]}");
    }

    @Test
    void parseRowsAcceptsLeadingDecimalNumbers() {
        List<String> columns = List.of("Rate");
        String raw = "{\"rows\":[{\"Rate\": .5}]}";
        assertThat(LlmStructuredJsonParser.parseRows(raw, columns).getRows())
                .containsExactly(Map.of("Rate", "0.5"));
    }

    @Test
    void parseRowsStripsEllipsisPlaceholderRows() {
        String raw = """
                {"rows":[
                  {"Name":"Ada"},
                  ...
                ]}
                """;
        assertThat(LlmStructuredJsonParser.parseRows(raw, List.of("Name")).getRows())
                .containsExactly(Map.of("Name", "Ada"));
    }

    @Test
    void parseRowsAcceptsSingleQuotedJson() {
        String raw = "{'rows':[{'Name':'Ada'}]}";
        assertThat(LlmStructuredJsonParser.parseRows(raw, List.of("Name")).getRows())
                .containsExactly(Map.of("Name", "Ada"));
    }

    @Test
    void parseIntoDeserializesChatEditPatchWithLeadingDecimal() {
        String raw = "{\"rows\":[{\"row\":7,\"values\":{\"Salary Net\": .5}}]}";

        ChatEditPatchResponse patch = LlmStructuredJsonParser.parseInto(raw, ChatEditPatchResponse.class);

        assertThat(patch.rows()).hasSize(1);
        assertThat(patch.rows().get(0).row()).isEqualTo(7);
        assertThat(patch.rows().get(0).values()).containsEntry("Salary Net", "0.5");
    }

    @Test
    void repairLeadingDecimalNumbersLeavesQuotedValuesAlone() {
        // A quoted string that merely contains ".5" must not be altered.
        assertThat(LlmStructuredJsonParser.repairLeadingDecimalNumbers("{\"note\":\"v.5\"}"))
                .isEqualTo("{\"note\":\"v.5\"}");
        assertThat(LlmStructuredJsonParser.repairLeadingDecimalNumbers("{\"x\": .5}"))
                .isEqualTo("{\"x\": 0.5}");
    }

    @Test
    void normalizeQuotesConvertsSingleQuotedJsonWithApostrophe() {
        // The exact class of failure the user hit: single-quoted JSON where a
        // value contains an apostrophe (ALLOW_SINGLE_QUOTES chokes on it).
        assertThat(LlmStructuredJsonParser.normalizeQuotes("{'name':'O'Brien'}"))
                .isEqualTo("{\"name\":\"O'Brien\"}");
    }

    @Test
    void normalizeQuotesEscapesInnerDoubleQuotesInSingleQuotedStrings() {
        assertThat(LlmStructuredJsonParser.normalizeQuotes("{'size':'6\" pipe'}"))
                .isEqualTo("{\"size\":\"6\\\" pipe\"}");
    }

    @Test
    void normalizeQuotesLeavesValidDoubleQuotedJsonUnchanged() {
        String valid = "{\"name\":\"O'Brien\",\"size\":\"6\\\" pipe\",\"n\":0.5}";
        assertThat(LlmStructuredJsonParser.normalizeQuotes(valid)).isEqualTo(valid);
    }

    @Test
    void parseRowsAcceptsSingleQuotedValuesWithApostrophes() {
        String raw = "{'rows':[{'Name':'O'Brien'},{'Name':'employee's data'}]}";
        assertThat(LlmStructuredJsonParser.parseRows(raw, List.of("Name")).getRows())
                .containsExactly(
                        Map.of("Name", "O'Brien"),
                        Map.of("Name", "employee's data"));
    }

    @Test
    void parseIntoDeserializesChatEditPatchWithSingleQuotesAndApostrophe() {
        String raw = "{'rows':[{'row':3,'values':{'Name':'O'Brien'}}]}";

        ChatEditPatchResponse patch = LlmStructuredJsonParser.parseInto(raw, ChatEditPatchResponse.class);

        assertThat(patch.rows()).hasSize(1);
        assertThat(patch.rows().get(0).row()).isEqualTo(3);
        assertThat(patch.rows().get(0).values()).containsEntry("Name", "O'Brien");
    }

    @Test
    void parseRowsRepairsMismatchedRowCloseBracket() {
        String raw = """
                {"rows":[
                  {"Emp ID":"E058","Full Name":"Harper","Email":"harper@example.com"],
                  {"Emp ID":"E059","Full Name":"Mia","Email":"mia@example.com"}
                ]}
                """;
        assertThat(LlmStructuredJsonParser.parseRows(raw, List.of("Emp ID", "Full Name", "Email"))
                .getRows()).hasSize(2);
    }

    // ---- ws28: prompt v2 row batches {"columns":[...],"rows":[[...]]} --------------

    @Test
    void parsesTheCompactArrayRowShapeAgainstTheFixedColumns() {
        String raw = "{\"columns\":[\"Name\",\"City\"],\"rows\":[[\"Ada\",\"London\"],[\"Bob\",\"\"]]}";
        List<Map<String, String>> rows = LlmStructuredJsonParser.parseRows(raw, List.of("Name", "City")).getRows();

        assertThat(rows).containsExactly(
                Map.of("Name", "Ada", "City", "London"),
                Map.of("Name", "Bob", "City", ""));
    }

    @Test
    void compactRowsFollowTheReplyColumnOrderWhenItNamesTheFixedSchema() {
        String raw = "```json\n{\"columns\":[\"City\",\"Name\"],\"rows\":[[\"London\",\"Ada\"]]}\n```";
        List<Map<String, String>> rows = LlmStructuredJsonParser.parseRows(raw, List.of("Name", "City")).getRows();

        assertThat(rows).containsExactly(Map.of("Name", "Ada", "City", "London"));
    }

    @Test
    void compactRowsWithUnknownReplyColumnsUseTheFixedSchemaAndPadShortRows() {
        String raw = "{\"columns\":[\"A\",\"B\"],\"rows\":[[\"Ada\"],[\"Bob\",\"Paris\",\"extra\"]]}";
        List<Map<String, String>> rows = LlmStructuredJsonParser.parseRows(raw, List.of("Name", "City")).getRows();

        assertThat(rows).containsExactly(
                Map.of("Name", "Ada", "City", ""),
                Map.of("Name", "Bob", "City", "Paris"));
    }

    @Test
    void compactRowsParseWithoutAFixedSchemaAndTheOldObjectShapeStillParses() {
        assertThat(LlmStructuredJsonParser.parseRows(
                "{\"columns\":[\"Name\"],\"rows\":[[\"Ada\"]]}", null).getRows())
                .containsExactly(Map.of("Name", "Ada"));
        // Prompt v1 replies (cached or from an older prompt) keep working.
        assertThat(LlmStructuredJsonParser.parseRows(
                "{\"rows\":[{\"Name\":\"Ada\",\"City\":\"London\"}]}", List.of("Name", "City")).getRows())
                .containsExactly(Map.of("Name", "Ada", "City", "London"));
    }
}
