package com.modocument.application.mapping;

import com.modocument.domain.entity.TemplateFields;

import java.util.List;
import java.util.Map;

/**
 * Everything the tiered mapper needs: the source columns, the template's
 * fields (with data types when loaded), a sample of parsed rows for the
 * value-shape tier, the template id for the learned-history tier, and the
 * owning user so that history tier only ever reads the caller's own past jobs.
 */
public record MappingContext(List<String> columns,
                             List<TemplateFields> fields,
                             List<Map<String, String>> sampleRows,
                             int templateId,
                             int ownerUserId,
                             Map<String, String> suggestedMappings) {

    public MappingContext(List<String> columns,
                          List<TemplateFields> fields,
                          List<Map<String, String>> sampleRows,
                          int templateId,
                          int ownerUserId) {
        this(columns, fields, sampleRows, templateId, ownerUserId, Map.of());
    }

    public MappingContext {
        if (suggestedMappings == null || suggestedMappings.isEmpty()) {
            suggestedMappings = Map.of();
        } else {
            suggestedMappings = new java.util.LinkedHashMap<>(suggestedMappings);
        }
    }
}
