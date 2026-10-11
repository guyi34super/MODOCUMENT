package com.modocument.infrastructure.integration.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON plumbing of the integration hub (ws25): connection config
 * (string values), flow specs (objects) and field mappings
 * ({@code [{from, to}]}).
 */
public final class IntegrationJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** One field-mapping pair: inbound bank column → template field; outbound field → bank column. */
    public record FieldMapping(String from, String to) {
    }

    private IntegrationJson() {
    }

    public static Map<String, Object> spec(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> value = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return value == null ? new LinkedHashMap<>() : value;
        } catch (Exception e) {
            throw new IllegalStateException("A stored flow spec is not valid JSON.");
        }
    }

    public static Map<String, String> config(String json) {
        Map<String, Object> raw = spec(json);
        Map<String, String> config = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                config.put(key, String.valueOf(value));
            }
        });
        return config;
    }

    public static List<FieldMapping> mapping(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<FieldMapping> value = MAPPER.readValue(json, new TypeReference<List<FieldMapping>>() {
            });
            return value == null ? List.of() : value;
        } catch (Exception e) {
            throw new IllegalStateException("A stored field mapping is not valid JSON.");
        }
    }

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize an integration value.", e);
        }
    }

    /**
     * Renames record keys by the mapping ({@code from → to}); with a mapping,
     * unmapped keys are dropped (only what the admin chose leaves or enters);
     * without one, records pass unchanged.
     */
    public static List<Map<String, Object>> applyMapping(List<Map<String, Object>> records,
                                                         List<FieldMapping> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return records;
        }
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (FieldMapping pair : mapping) {
                if (pair.from() != null && pair.to() != null && record.containsKey(pair.from())) {
                    mapped.put(pair.to(), record.get(pair.from()));
                }
            }
            out.add(mapped);
        }
        return out;
    }
}
