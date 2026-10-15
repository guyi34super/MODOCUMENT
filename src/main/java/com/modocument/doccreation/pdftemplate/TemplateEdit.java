package com.modocument.doccreation.pdftemplate;

/**
 * A change to the original PDF that produces the template PDF (data removal or a user edit).
 * {@code regionKey} links a DATA_REMOVAL to the DATA_REMOVED region proposed for the same value (the
 * field that takes the removed value's place), so restoring the removal can drop its field and applying a
 * suggested removal can add it; null for user edits and when no region was proposed.
 */
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
        String removedText,
        String regionKey) {

    /** An edit without a linked region. */
    public TemplateEdit(String id, EditKind kind, PdfBox box, String text, String fontName, Float fontSize,
                        String color, String imageKey, EditSource source, double confidence, String removedText) {
        this(id, kind, box, text, fontName, fontSize, color, imageKey, source, confidence, removedText, null);
    }
}
