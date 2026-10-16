package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A PDF template's field rules (docs/doccreation/field-validation-contract.md
 * §3): the reply of {@code GET/PUT /blueprints/{id}/rules} and
 * {@code POST /rules/import}. {@code revision} is the template's
 * RulesRevision (the {@code baseRevision} of the next save), {@code fields}
 * the current version's regions in sort order and {@code ruleTypes} what
 * each rule type applies to (the editor builds its forms from it).
 */
public record RulesDto(int revision,
                       List<Rule> rules,
                       List<Field> fields,
                       List<RuleType> ruleTypes) {

    /**
     * One rule; {@code orphan} is true when its region key is not in the
     * current version. {@code sourceTemplateName} is null when the source
     * flow template is gone or not readable by the caller.
     */
    public record Rule(Long id,
                       String regionKey,
                       String ruleType,
                       JsonNode config,
                       String message,
                       String severity,
                       String origin,
                       Integer sourceTemplateId,
                       String sourceTemplateName,
                       String sourceFieldName,
                       int sortOrder,
                       boolean orphan) {
    }

    /** A region of the current version ({@code page} 0-based, as in RegionDto). */
    public record Field(String key, String label, String kind, int page) {
    }

    /** A rule type, the field kinds it applies to and its config keys. */
    public record RuleType(String type, List<String> kinds, List<String> configKeys) {
    }
}
