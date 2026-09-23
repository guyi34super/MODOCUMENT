package com.modocument.infrastructure.extraction.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jackson (de)serialization for the JSON text columns of the SQL extraction
 * store. Null-safe in both directions so absent configs stay null in the
 * database and absent columns come back as null/empty structures.
 */
final class JsonColumns {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
            new TypeReference<>() {
            };

    private JsonColumns() {
    }

    static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }

    static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize " + type.getSimpleName(), e);
        }
    }

    static Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize string map column", e);
        }
    }

    /** Parses a domain string id into the SQL identity value; empty when not numeric. */
    static Long parseId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
