package com.modocument.application.dto;

import jakarta.validation.constraints.NotNull;

/** JSON body of PUT /api/admin/users/{id}/disabled (ws09: the leaver switch). */
public class UpdateUserDisabledRequest {

    @NotNull
    private Boolean disabled;

    public UpdateUserDisabledRequest() {
    }

    public UpdateUserDisabledRequest(Boolean disabled) {
        this.disabled = disabled;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }
}
