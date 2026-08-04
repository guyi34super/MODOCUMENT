package com.modocument.application.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Accepts {@code {"rows":[...]}}, {@code [...]}, or {@code {"columns":...,"rows":...}} from LLMs. */
public class TableRowsResultDeserializer extends JsonDeserializer<TableRowsResult> {

    private static final TypeReference<List<Map<String, String>>> ROW_LIST =
            new TypeReference<>() {
            };

    @Override
    public TableRowsResult deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        TableRowsResult result = new TableRowsResult();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            result.setRows(((com.fasterxml.jackson.databind.ObjectMapper) parser.getCodec())
                    .convertValue(node, ROW_LIST));
            return result;
        }
        if (node.isObject()) {
            JsonNode rowsNode = node.get("rows");
            if (rowsNode != null && rowsNode.isArray()) {
                result.setRows(((com.fasterxml.jackson.databind.ObjectMapper) parser.getCodec())
                        .convertValue(rowsNode, ROW_LIST));
            }
        }
        return result;
    }
}
