package com.modocument.doccreation.pdftemplate;

/** A PDF the template engine cannot use; {@code code} is one of ENCRYPTED, TOO_MANY_PAGES, NOT_A_PDF, UNREADABLE. */
public class PdfTemplateException extends RuntimeException {

    private final String code;

    public PdfTemplateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PdfTemplateException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
