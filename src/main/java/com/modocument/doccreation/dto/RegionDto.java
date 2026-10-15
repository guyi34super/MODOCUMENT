package com.modocument.doccreation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

/**
 * A fill-in region as the frontend sees it (contract §5): the box as
 * top-left fractions of the displayed (rotated) page image. Used for
 * responses and as the input of {@code PUT /blueprints/{id}/overlay}.
 * {@code sampleValue} (the removed value of a WITH_DATA upload) is present
 * only while the template is a DRAFT under review, and ignored on input.
 *
 * <p>{@code suggested} (BUG-PDF-01): true for the DATA_REMOVED region of a
 * SUGGESTED removal ({@link EditDto#applied()} false, linked through
 * {@link EditDto#regionKey()}): it is shown for review but is not part of
 * the template unless the client keeps it (sends it with
 * {@code suggested:false} or null). A region sent with
 * {@code suggested:true} is ignored.
 */
public record RegionDto(
        @Size(max = 64) String key,
        @Size(max = 200) String label,
        @Size(max = 120) String group,
        String kind,
        int page,
        double fx,
        double fy,
        double fw,
        double fh,
        @Size(max = 64) String fontName,
        Float fontSize,
        @Size(max = 7) String color,
        String align,
        Integer maxLines,
        @Size(max = 255) String acroFieldName,
        boolean required,
        Integer sortOrder,
        String source,
        Double confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sampleValue,
        Boolean suggested) {

    /** True only for a suggested region sent back unkept ({@code suggested:true}). */
    public boolean treatedAsSuggested() {
        return suggested != null && suggested;
    }
}
