package com.modocument.application.port;

/**
 * Why a prompt is being sent (SUP-S13). Every LLM call names its purpose so
 * the AI gateway can audit it and apply the right output-scanning policy
 * (gate C10): STRICT purposes write content a human will read or release, so
 * a completion that leaks an identifier shape or echoes injected
 * instructions is REJECTED and the caller falls back to its non-AI path;
 * LENIENT purposes produce suggestions that are validated downstream
 * (column names, schema names), so a suspicious completion is scrubbed and
 * flagged instead of discarded.
 */
public enum AiPurpose {

    /** Unstructured text → table (ingestion parse). Output becomes data rows. */
    TABLE_EXTRACTION(OutputPolicy.STRICT, true),
    /** Legacy transform endpoint: classify + extract fields to JSON. */
    TRANSFORM_EXTRACTION(OutputPolicy.STRICT, true),
    /** File snippet → template id (an integer; nothing to leak downstream). */
    TEMPLATE_CLASSIFY(OutputPolicy.LENIENT, true),
    /** Column → field mapping suggestions; names validated downstream. */
    AUTO_MAP(OutputPolicy.LENIENT, true),
    /** Chat-driven table edits; the patch is written into the user's data. */
    CHAT_EDIT(OutputPolicy.STRICT, true),
    /** LLM record cleaning; a rejected batch keeps the original rows. */
    DATA_CLEANING(OutputPolicy.STRICT, true),
    /** Template schema suggestion from samples; reviewed before use. */
    SCHEMA_INFERENCE(OutputPolicy.LENIENT, true),
    /** Prose summary of an extraction run. */
    EXTRACTION_SUMMARY(OutputPolicy.LENIENT, true),
    /** Blueprint section breakdown; falls back to the heuristic parser. */
    DOC_SECTION_SPLIT(OutputPolicy.STRICT, true),
    /** Strip sample values from an uploaded template; heuristics remain primary. */
    DOC_TEMPLATE_CLEAN(OutputPolicy.STRICT, true),
    /** Detect fillable fields inside a FORM template section. */
    DOC_TEMPLATE_FIELD(OutputPolicy.LENIENT, true),
    /**
     * Exact PDF templates: labels, groups and kinds for the fill-in regions the
     * code found. Suggestions only: every id is re-validated against the page
     * model and the code result stands on any failure.
     */
    DOC_PDF_REGIONS(OutputPolicy.LENIENT, true),
    /**
     * Exact PDF templates: which text runs of a filled upload are sample data.
     * Decides what is cut from the template, so a suspicious completion is
     * rejected and the code classification stands.
     */
    DOC_PDF_DATA(OutputPolicy.STRICT, true),
    /** AI-composed document generation; falls back to the deterministic engine. */
    DOC_COMPOSE(OutputPolicy.STRICT, true),
    /**
     * Vision OCR of an uploaded page. The completion IS the document's own
     * text, so identifier shapes in it are the customer's data coming back
     * to the backend, not a leak — the identifier scan is off and the text
     * is tokenized like any other extracted text before later prompts.
     */
    VISION_OCR(OutputPolicy.LENIENT, false),
    /** The raw-prompt diagnostics endpoint (POST /api/aifiles/send). */
    DIAGNOSTIC(OutputPolicy.LENIENT, true);

    /** How gate C10 treats a suspicious completion for this purpose. */
    public enum OutputPolicy {
        /** Reject the completion; the caller falls back to its non-AI path. */
        STRICT,
        /** Scrub identifier shapes, flag in the audit trail, and continue. */
        LENIENT
    }

    private final OutputPolicy outputPolicy;
    private final boolean scanIdentifiers;

    AiPurpose(OutputPolicy outputPolicy, boolean scanIdentifiers) {
        this.outputPolicy = outputPolicy;
        this.scanIdentifiers = scanIdentifiers;
    }

    public OutputPolicy outputPolicy() {
        return outputPolicy;
    }

    /** False only where identifier shapes in the output are the document's own content. */
    public boolean scanIdentifiers() {
        return scanIdentifiers;
    }
}
