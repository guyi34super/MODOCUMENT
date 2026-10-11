package com.modocument.infrastructure.extraction.connector;

import com.modocument.connector.core.support.JsonFlattener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFlattenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }

    @Test
    void recordsAtDotPathFlattenNestedObjects() throws Exception {
        JsonNode root = json("""
                {"data":{"items":[
                  {"id":1,"user":{"name":"Ada","address":{"city":"London"}}},
                  {"id":2,"user":{"name":"Alan"}}
                ]}}
                """);

        List<Map<String, String>> records = JsonFlattener.records(root, "data.items");

        assertThat(records).hasSize(2);
        assertThat(records.get(0))
                .containsEntry("id", "1")
                .containsEntry("user.name", "Ada")
                .containsEntry("user.address.city", "London");
    }

    @Test
    void rootArrayAndEmptyPathWork() throws Exception {
        List<Map<String, String>> records =
                JsonFlattener.records(json("[{\"a\":1},{\"a\":2}]"), "");

        assertThat(records).hasSize(2);
    }

    @Test
    void nestedArraysStayAsJsonStringsAndNullsBecomeEmpty() throws Exception {
        List<Map<String, String>> records = JsonFlattener.records(
                json("[{\"tags\":[\"x\",\"y\"],\"note\":null}]"), null);

        assertThat(records.get(0))
                .containsEntry("tags", "[\"x\",\"y\"]")
                .containsEntry("note", "");
    }

    @Test
    void missingPathYieldsNoRecords() throws Exception {
        assertThat(JsonFlattener.records(json("{\"a\":1}"), "does.not.exist")).isEmpty();
    }

    @Test
    void singleObjectAtPathBecomesOneRecord() throws Exception {
        assertThat(JsonFlattener.records(json("{\"result\":{\"a\":\"1\"}}"), "result"))
                .containsExactly(Map.of("a", "1"));
    }
}
