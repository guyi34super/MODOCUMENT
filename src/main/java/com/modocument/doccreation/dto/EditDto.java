package com.modocument.doccreation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

/**
 * A template edit as the frontend sees it (contract §5): REMOVE,
 * REPLACE_TEXT, ADD_TEXT or ADD_IMAGE, the box as top-left fractions of the
 * displayed page image. {@code imageKey} names an image uploaded through
 * {@code POST /blueprints/{id}/edit-images} (ADD_IMAGE). {@code removedText}
 * is present only while the template is a DRAFT, and ignored on input.
 */
public record EditDto(
        @Size(max = 64) String id,
        String kind,
        int page,
        double fx,
        double fy,
        double fw,
        double fh,
        @Size(max = 2000) String text,
        @Size(max = 64) String fontName,
        Float fontSize,
        @Size(max = 7) String color,
        @Size(max = 128) String imageKey,
        String source,
        Double confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) String removedText) {
}
