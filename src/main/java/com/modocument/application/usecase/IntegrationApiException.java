package com.modocument.application.usecase;

/**
 * A refusal of the integration API (ws26) with a stable machine code — the
 * {@code code} of the {@code {error, code, message}} error body bank systems
 * branch on (docs/integration/developer-guide.md lists them).
 */
public class IntegrationApiException extends RuntimeException {

    private final int status;
    private final String code;

    public IntegrationApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static IntegrationApiException notFound(String what) {
        return new IntegrationApiException(404, "NOT_FOUND", what + " not found.");
    }

    public static IntegrationApiException badRequest(String code, String message) {
        return new IntegrationApiException(400, code, message);
    }

    public static IntegrationApiException conflict(String code, String message) {
        return new IntegrationApiException(409, code, message);
    }

    public static IntegrationApiException tooLarge(String message) {
        return new IntegrationApiException(413, "PAYLOAD_TOO_LARGE", message);
    }
}
