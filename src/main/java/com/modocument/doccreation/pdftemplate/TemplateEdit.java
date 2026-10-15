package com.modocument.doccreation.pdftemplate;

/** A change to the original PDF that produces the template PDF (data removal or a user edit). */
public record TemplateEdit(
        String id,
        EditKind kind,
        PdfBox box,
        String text,
        String fontName,
        Float fontSize,
        String color,
        String imageKey,
        EditSource source,
        double confidence,
        String removedText) {
}
