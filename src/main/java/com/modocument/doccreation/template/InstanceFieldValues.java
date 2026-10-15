package com.modocument.doccreation.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Form fill values stored on {@code DocInstance.FieldValuesJson} (V292). */
public final class InstanceFieldValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Entry(int sectionId, String fieldKey, String value) {
    }

    private InstanceFieldValues() {
    }

    public static List<Entry> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Entry> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String write(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize field values.", e);
        }
    }

    /** Keys are {@code sectionId + ":" + fieldKey}. */
    public static Map<String, String> indexBySectionAndKey(List<Entry> entries) {
        Map<String, String> map = new HashMap<>();
        if (entries == null) {
            return map;
        }
        for (Entry entry : entries) {
            if (entry.fieldKey() == null || entry.fieldKey().isBlank()) {
                continue;
            }
            map.put(entry.sectionId() + ":" + entry.fieldKey(),
                    entry.value() == null ? "" : entry.value());
        }
        return map;
    }

    public static String compositeKey(int sectionId, String fieldKey) {
        return sectionId + ":" + fieldKey;
    }
}
