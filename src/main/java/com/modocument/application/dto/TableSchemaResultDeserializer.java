package com.modocument.application.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/** Accepts {@code {"columns":[...]}}, {@code [...]}, or full {@code TableData} shapes from LLMs. */
public class TableSchemaResultDeserializer extends JsonDeserializer<TableSchemaResult> {

    private static final TypeReference<List<String>> COLUMN_LIST = new TypeReference<>() {
    };

    @Override
    public TableSchemaResult deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        TableSchemaResult result = new TableSchemaResult();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            result.setColumns(((com.fasterxml.jackson.databind.ObjectMapper) parser.getCodec())
                    .convertValue(node, COLUMN_LIST));
            return result;
        }
        if (node.isObject()) {
            JsonNode columnsNode = node.get("columns");
            if (columnsNode != null && columnsNode.isArray()) {
                result.setColumns(((com.fasterxml.jackson.databind.ObjectMapper) parser.getCodec())
                        .convertValue(columnsNode, COLUMN_LIST));
            }
        }
        return result;
    }
}
