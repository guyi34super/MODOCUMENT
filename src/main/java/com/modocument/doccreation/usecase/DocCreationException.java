package com.modocument.doccreation.usecase;

/**
 * A document-creation refusal that carries its HTTP status and a stable
 * machine-readable {@code code} (docs/doccreation/pdf-templates-contract.md
 * §5): the controllers answer {@code {"error": message, "code": code}} with
 * {@link #status()}. Ordinary 400/404/409 cases keep using
 * IllegalArgumentException / NoSuchElementException / IllegalStateException.
 */
public class DocCreationException extends RuntimeException {

    /** A Word-based template (or a document built on one) after the PDF switch. */
    public static final String LEGACY_TEMPLATE = "LEGACY_TEMPLATE";
    /** The generated PDF failed the exactness self-check and was refused. */
    public static final String EXACTNESS_FAILED = "EXACTNESS_FAILED";
    /** No PdfTemplateEngine bean is wired (never in a complete build). */
    public static final String ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE";
    /** ws33: generate/download of a document that has not passed validation since its last change. */
    public static final String NOT_VALIDATED = "NOT_VALIDATED";

    private final int status;
    private final String code;

    public DocCreationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public DocCreationException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** 410: the retired Word path. */
    public static DocCreationException legacy() {
        return new DocCreationException(410, LEGACY_TEMPLATE,
                "This template was made for the retired Word path and can no longer be opened. "
                        + "Upload the document again as a PDF; an administrator can purge the old "
                        + "Word templates and their documents.");
    }

    /** 400 with the engine's code (NOT_A_PDF, ENCRYPTED, TOO_MANY_PAGES, UNREADABLE). */
    public static DocCreationException badPdf(String code, String message) {
        return new DocCreationException(400, code, message);
    }

    /** 409: the document must be saved and validated first (field-validation-contract §4). */
    public static DocCreationException notValidated() {
        return new DocCreationException(409, NOT_VALIDATED,
                "Save and validate the document first: it has changes that have not passed its checks.");
    }

    /**
     * 409: the document was validated, but its template's rules, data
     * template or mapping changed since (data-templates-contract §5).
     */
    public static DocCreationException rulesChanged() {
        return new DocCreationException(409, NOT_VALIDATED,
                "The rules changed since this document was validated; save it again to re-check.");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
