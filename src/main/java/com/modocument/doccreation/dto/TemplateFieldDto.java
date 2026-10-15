package com.modocument.doccreation.dto;

/** One fillable slot in a FORM blueprint section (V292). */
public record TemplateFieldDto(String fieldKey,
                               String label,
                               int anchorParagraphIndex,
                               String valueStyle,
                               String value) {
}
