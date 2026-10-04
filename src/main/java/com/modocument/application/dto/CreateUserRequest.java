package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/admin/users (ws09). The bank is never part of the
 * request — the new user is created in the calling Admin's own bank. The
 * temporary password goes through the full password policy and the account
 * is created with MustChangePassword set.
 */
public class CreateUserRequest {

    @NotBlank
    @Size(max = 256)
    private String email;

    @Size(max = 200)
    private String displayName;

    /** ADMIN, APPROVER, READ_WRITE or READ_ONLY. */
    @NotBlank
    @Size(max = 20)
    private String role;

    /** PasswordPolicy enforces the real rules; this is only the schema cap. */
    @NotBlank
    @Size(max = 200)
    private String temporaryPassword;

    public CreateUserRequest() {
    }

    public CreateUserRequest(String email, String displayName, String role,
                             String temporaryPassword) {
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.temporaryPassword = temporaryPassword;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTemporaryPassword() {
        return temporaryPassword;
    }

    public void setTemporaryPassword(String temporaryPassword) {
        this.temporaryPassword = temporaryPassword;
    }
}
