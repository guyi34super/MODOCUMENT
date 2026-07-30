package com.modocument.application.mapping;

import com.modocument.domain.entity.TemplateFields;

import java.util.List;
import java.util.Map;

/**
 * Everything the tiered mapper needs: the source columns, the template's
 * fields (with data types when loaded), a sample of parsed rows for the
 * value-shape tier, and the template id for the learned-history tier.
 */
public record MappingContext(List<String> columns,
                             List<TemplateFields> fields,
                             List<Map<String, String>> sampleRows,
                             int templateId) {
}
