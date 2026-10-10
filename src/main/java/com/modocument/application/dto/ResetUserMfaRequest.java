package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/admin/users/{id}/mfa-reset (phase 2, ws22): why
 * the user's second factor is being removed (lost device, new phone...).
 * The checker reads it in the approval queue and it lands in the audit row.
 */
public class ResetUserMfaRequest {

    @NotBlank
    @Size(max = 500)
    private String reason;

    public ResetUserMfaRequest() {
    }

    public ResetUserMfaRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
