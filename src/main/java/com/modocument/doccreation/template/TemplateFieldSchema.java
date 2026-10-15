package com.modocument.doccreation.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** JSON field definitions stored on {@code DocBlueprintSection.FieldSchema} (V292). */
public final class TemplateFieldSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record FieldDef(String fieldKey,
                           String label,
                           int anchorParagraphIndex,
                           String valueStyle) {
        public static final String STYLE_INLINE = "INLINE";
        public static final String STYLE_TAB_CELL = "TAB_CELL";
        public static final String STYLE_NEXT_PARAGRAPH = "NEXT_PARAGRAPH";

        public String normalizedStyle() {
            if (valueStyle == null || valueStyle.isBlank()) {
                return STYLE_INLINE;
            }
            return valueStyle.strip().toUpperCase(Locale.ROOT);
        }
    }

    private TemplateFieldSchema() {
    }

    public static List<FieldDef> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<FieldDef> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String write(List<FieldDef> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize field schema.", e);
        }
    }

    public static String slugKey(String label) {
        if (label == null || label.isBlank()) {
            return "field";
        }
        String slug = label.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", "_")
                .replaceAll("^_|_$", "");
        if (slug.isBlank()) {
            return "field";
        }
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }

    public static List<FieldDef> dedupeKeys(List<FieldDef> fields) {
        List<FieldDef> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (FieldDef field : fields) {
            String key = field.fieldKey();
            int suffix = 2;
            while (!seen.add(key)) {
                key = field.fieldKey() + "_" + suffix++;
            }
            out.add(new FieldDef(key, field.label(), field.anchorParagraphIndex(), field.valueStyle()));
        }
        return out;
    }
}
