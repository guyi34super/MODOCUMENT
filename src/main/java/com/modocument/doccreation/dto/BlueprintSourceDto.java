package com.modocument.doccreation.dto;

import java.util.List;
import java.util.Map;

/**
 * A saved data source of a PDF template (contract §4): the flow template,
 * its role (PRIMARY or LINKED) and link fields, the mapping (region key to
 * template field name), the template's fields with their data types, and
 * suggestions for regions that are not mapped yet. {@code readable} is false
 * when the caller can no longer read the template (it was made private by
 * its owner): the source can then only be removed.
 */
public record BlueprintSourceDto(long id,
                                 int templateId,
                                 String templateName,
                                 String role,
                                 String linkFieldPrimary,
                                 String linkFieldThis,
                                 Map<String, String> mapping,
                                 List<FieldDto> fields,
                                 List<SuggestionDto> suggestions,
                                 boolean readable) {

    /** A field of the flow template and its data type name (Text, Number, Date, …). */
    public record FieldDto(String name, String dataType) {
    }

    /** An auto-mapping candidate for a region: {@code sameName} when the names match after normalisation. */
    public record SuggestionDto(String regionKey, String fieldName, double score, boolean sameName) {
    }
}
