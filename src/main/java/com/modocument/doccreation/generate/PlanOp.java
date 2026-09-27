package com.modocument.doccreation.generate;

/**
 * One step of an AI-composed assembly plan, already validated and normalized:
 * KEEP carries an inclusive range of original body-element indexes; PARAGRAPH
 * and IMAGE carry the (resolved) content to insert at that point. Because
 * validation forces the KEEP ranges to cover every original element exactly
 * once, in order, rendering a plan reproduces the original document exactly —
 * the AI only ever decides where the additions go and how their text reads.
 */
public record PlanOp(Kind kind, int from, int to, Integer assetId, String text, String caption) {

    public enum Kind { KEEP, PARAGRAPH, IMAGE }

    public static PlanOp keep(int from, int to) {
        return new PlanOp(Kind.KEEP, from, to, null, null, null);
    }

    public static PlanOp paragraph(String text) {
        return new PlanOp(Kind.PARAGRAPH, -1, -1, null, text, null);
    }

    public static PlanOp image(int assetId, String caption) {
        return new PlanOp(Kind.IMAGE, -1, -1, assetId, null, caption);
    }
}
