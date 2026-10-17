package com.modocument.doccreation.dto;

import java.util.List;

/**
 * A PDF template's data template and field mapping
 * (docs/doccreation/data-templates-contract.md §3): the reply of
 * {@code PUT /blueprints/{id}/data-template} and {@code GET/PUT …/data-mapping}.
 *
 * <p>{@code revision} is the template's RulesRevision; {@code rows} are the
 * regions of the current version in sort order with their box as fractions
 * of the displayed page (as in {@link RegionDto}), the data field mapped to
 * each ({@code mappedField}, null when none) and the best one-to-one
 * suggestion ({@code suggestion}, null when none reaches the threshold);
 * {@code fields} are the data template's fields. {@code mappableCount}
 * counts the regions that have at least one compatible field,
 * {@code mappedCount} the regions mapped to a field of the data template.
 */
public record DataMappingDto(Integer dataTemplateId,
                             String dataTemplateName,
                             int revision,
                             int mappedCount,
                             int mappableCount,
                             List<Row> rows,
                             List<Field> fields) {

    /** A region of the current version and its data field ({@code page} 0-based, as in RegionDto). */
    public record Row(String regionKey,
                      String label,
                      String kind,
                      int page,
                      double fx,
                      double fy,
                      double fw,
                      double fh,
                      String mappedField,
                      Suggestion suggestion) {
    }

    /** A suggested data field: {@code sameName} when the names match after normalisation (score 1.0). */
    public record Suggestion(String field, double score, boolean sameName) {
    }

    /** A field of the data template. */
    public record Field(String name, String label, String kind) {
    }
}
