package com.modocument.application.dto;

import jakarta.validation.constraints.Size;

/** JSON body of PUT /api/auth/profile. */
public class UpdateProfileRequest {

    /** AppUsers.DisplayName is varchar(200). */
    @Size(max = 200)
    private String displayName;

    /** Free-form settings JSON; bounded so no one parks megabytes in it. */
    @Size(max = 20_000)
    private String settings;

    public UpdateProfileRequest() {
    }

    public UpdateProfileRequest(String displayName, String settings) {
        this.displayName = displayName;
        this.settings = settings;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }
}
