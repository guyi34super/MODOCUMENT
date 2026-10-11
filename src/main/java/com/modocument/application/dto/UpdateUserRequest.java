package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of PATCH /api/admin/users/{id} (phase 2, ws22). Only the display
 * name is editable: the email is the login identity (and, for OIDC accounts,
 * the IdP link), so it is deliberately not part of this request — an
 * {@code email} property in the body is ignored like any unknown field.
 */
public class UpdateUserRequest {

    @NotBlank
    @Size(max = 200)
    private String displayName;

    public UpdateUserRequest() {
    }

    public UpdateUserRequest(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
