package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code GET /instances/{id}/fill-preview} (contract §4): what filling the
 * document from a record would write. {@code mappingSource} is SAVED when
 * the record's template is a saved source of the template, AUTO when the
 * mapping was built on the fly. {@code linked} lists the other saved sources
 * and the record found for each (newest wins, the rest are
 * {@code alternatives}); {@code fields} lists only regions that receive a
 * value, masked as the caller sees it.
 */
public record FillPreviewDto(long recordId,
                             int templateId,
                             String mappingSource,
                             List<LinkedDto> linked,
                             List<FieldDto> fields) {

    /** A linked source: the record matched by {@code matchedBy} (null when none matched). */
    public record LinkedDto(int templateId, String templateName, String matchedBy, Long recordId,
                            List<Long> alternatives) {
    }

    /** A region that receives a value. */
    public record FieldDto(String regionKey, String label, String value, String currentValue,
                           int sourceTemplateId, String sourceField, boolean willOverwrite, boolean sameName) {
    }
}
