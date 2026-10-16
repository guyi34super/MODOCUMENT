package com.modocument.doccreation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What is checked on one field of a document, shown in the builder
 * (field-validation-contract §4.4): the template's rules for the field with
 * a plain-language summary, the checks its kind and properties imply, and
 * the flow fields it is filled from (the template's data sources). Built from
 * the template only; never contains a document value.
 */
public record FieldChecksDto(List<RuleView> rules, List<String> builtIn, List<MappedFrom> mappedFrom) {

    /** A field rule; {@code sourceTemplateName}/{@code sourceFieldName} only for IMPORTED rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleView(Long id, String ruleType, String summary, String severity, String origin,
                           String sourceTemplateName, String sourceFieldName) {
    }

    /** A data source mapping onto this field: flow template and field, PRIMARY or LINKED. */
    public record MappedFrom(int templateId, String templateName, String fieldName, String role) {
    }
}
