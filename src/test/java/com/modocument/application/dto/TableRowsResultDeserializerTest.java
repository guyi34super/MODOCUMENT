package com.modocument.application.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableRowsResultDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRowsObject() throws Exception {
        String json = "{\"rows\":[{\"Name\":\"Ada\",\"City\":\"London\"}]}";
        TableRowsResult result = mapper.readValue(json, TableRowsResult.class);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0)).containsEntry("Name", "Ada");
    }

    @Test
    void parsesBareArray() throws Exception {
        String json = "[{\"Name\":\"Ada\",\"City\":\"London\"},{\"Name\":\"Grace\",\"City\":\"Paris\"}]";
        TableRowsResult result = mapper.readValue(json, TableRowsResult.class);
        assertThat(result.getRows()).hasSize(2);
    }

    @Test
    void parsesTableDataShape() throws Exception {
        String json = """
                {"columns":["Name","City"],"rows":[{"Name":"Ada","City":"London"}]}
                """;
        TableRowsResult result = mapper.readValue(json, TableRowsResult.class);
        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void parsesBareColumnArrayForSchema() throws Exception {
        String json = "[\"Emp ID\",\"Full Name\",\"Department\"]";
        TableSchemaResult result = mapper.readValue(json, TableSchemaResult.class);
        assertThat(result.getColumns()).containsExactly("Emp ID", "Full Name", "Department");
    }
}
