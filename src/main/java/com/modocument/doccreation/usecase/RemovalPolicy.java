package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.TemplateEdit;

/**
 * Which proposed data removals of a WITH_DATA upload are applied to the
 * draft template (BUG-PDF-01, contract §5). A removal at or above
 * {@link #APPLY_THRESHOLD} is applied; a lower-confidence guess (e.g. a
 * free-standing e-mail or date that may be printed form text) is only
 * SUGGESTED: shown for review with {@code applied:false}, never applied
 * unless the client sends it back as an applied edit.
 */
public final class RemovalPolicy {

    /** Confidence from which a proposed removal is applied by default (the engine's review line). */
    public static final double APPLY_THRESHOLD = 0.6;

    private RemovalPolicy() {
    }

    /** True when the proposed removal is applied to the draft; false when it is only suggested. */
    public static boolean appliesByDefault(TemplateEdit removal) {
        return removal.confidence() >= APPLY_THRESHOLD;
    }
}
