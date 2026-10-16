package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * What {@code POST /rules/import} would copy (field-validation-contract §3,
 * {@code GET /blueprints/{id}/rules/import-preview}). {@code source} is
 * TEMPLATE (one chosen flow template, auto-matched) or DATA_SOURCES (the
 * template's saved data sources, one entry per region and source; the
 * top-level template id and name are then null and each field carries its
 * source's). {@code templateFields} are the flow template fields (of every
 * source for DATA_SOURCES).
 */
public record ImportPreviewDto(String source,
                               Integer templateId,
                               String templateName,
                               List<Field> fields,
                               List<String> templateFields) {

    /**
     * One mapped region: the flow field it takes its rules from, the match
     * score (null for a saved data-source mapping), the converted rules and
     * the flow rules that are skipped.
     */
    public record Field(String regionKey,
                        String label,
                        String fieldName,
                        Double score,
                        boolean sameName,
                        Integer templateId,
                        String templateName,
                        List<PreviewRule> rules,
                        List<Skipped> skipped) {
    }

    /** A rule as it would be imported. */
    public record PreviewRule(String ruleType, JsonNode config, String message) {
    }

    /** A flow rule that is not imported, and why. */
    public record Skipped(String flowRuleType, String reason) {
    }
}
