package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.doccreation.fields.FieldProps;

import java.util.List;

/**
 * A data template with its fields and rules
 * (docs/doccreation/data-templates-contract.md §2): the reply of every route
 * that reads or changes one. {@code revision} is its RulesRevision (the
 * {@code baseRevision} of the next fields or rules save), {@code usedBy} the
 * PDF templates that chose it and {@code ruleTypes} what each rule type
 * applies to, as in the ws33 {@link RulesDto}.
 */
public record DataTemplateDto(int id,
                              String name,
                              String description,
                              int revision,
                              Integer sourceTemplateId,
                              String sourceTemplateName,
                              List<Field> fields,
                              List<Rule> rules,
                              List<UsedBy> usedBy,
                              List<RulesDto.RuleType> ruleTypes) {

    /** A data field; {@code props} is null when no properties are set. */
    public record Field(String name,
                        String label,
                        String kind,
                        String piiKind,
                        FieldProps props,
                        String sourceFieldName,
                        int sortOrder) {
    }

    /**
     * A rule of a data field. {@code sourceTemplateName} is null when the
     * source flow template is gone or not readable by the caller.
     */
    public record Rule(Long id,
                       String fieldName,
                       String ruleType,
                       JsonNode config,
                       String message,
                       String severity,
                       String origin,
                       Integer sourceTemplateId,
                       String sourceTemplateName,
                       String sourceFieldName,
                       int sortOrder) {
    }

    /** A PDF template that chose this data template. */
    public record UsedBy(int blueprintId, String name) {
    }
}
