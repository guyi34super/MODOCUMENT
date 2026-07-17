package com.modocument.application.dto;

/** JSON body of PUT /api/auth/profile. */
public class UpdateProfileRequest {

    private String displayName;
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
