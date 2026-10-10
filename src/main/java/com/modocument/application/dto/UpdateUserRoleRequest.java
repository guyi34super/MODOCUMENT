package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of PUT /api/admin/users/{id}/role (ws09). */
public class UpdateUserRoleRequest {

    /** ADMIN, APPROVER, READ_WRITE or READ_ONLY. */
    @NotBlank
    @Size(max = 20)
    private String role;

    public UpdateUserRoleRequest() {
    }

    public UpdateUserRoleRequest(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
