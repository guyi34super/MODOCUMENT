package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/admin/users/{id}/password-reset (phase 2, ws22).
 * The temporary password goes through the full password policy when the
 * maker submits, and only its hash is kept in the pending change.
 */
public class ResetUserPasswordRequest {

    /** PasswordPolicy enforces the real rules; this is only the schema cap. */
    @NotBlank
    @Size(max = 200)
    private String temporaryPassword;

    public ResetUserPasswordRequest() {
    }

    public ResetUserPasswordRequest(String temporaryPassword) {
        this.temporaryPassword = temporaryPassword;
    }

    public String getTemporaryPassword() {
        return temporaryPassword;
    }

    public void setTemporaryPassword(String temporaryPassword) {
        this.temporaryPassword = temporaryPassword;
    }
}
