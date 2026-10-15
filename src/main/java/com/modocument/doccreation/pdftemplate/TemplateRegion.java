package com.modocument.doccreation.pdftemplate;

/**
 * A fill-in area of a PDF template. {@code sampleValue} carries the removed value while a
 * WITH_DATA upload is under review and is never stored past it.
 */
public record TemplateRegion(
        String key,
        String label,
        String group,
        RegionKind kind,
        PdfBox box,
        String fontName,
        float fontSize,
        String color,
        Align align,
        int maxLines,
        String acroFieldName,
        boolean required,
        int sortOrder,
        RegionSource source,
        double confidence,
        String sampleValue) {
}
