package com.modocument.application.dto;

import com.modocument.domain.model.TemplateVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * JSON body of PUT /Templates/{id}/Visibility (ws22):
 * {@code {"visibility": "PRIVATE" | "BANK"}}.
 */
public class TemplateVisibilityRequest {

    @NotBlank
    @Size(max = 10)
    private String visibility;

    public TemplateVisibilityRequest() {
    }

    public TemplateVisibilityRequest(String visibility) {
        this.visibility = visibility;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    /**
     * The requested visibility, case-insensitively.
     *
     * @throws IllegalArgumentException (400) for anything but PRIVATE / BANK
     */
    public TemplateVisibility parsed() {
        return parse(visibility);
    }

    /** PRIVATE / BANK by name, case-insensitively; null/blank stays null. */
    public static TemplateVisibility parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TemplateVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "Unknown visibility '" + value + "'; use PRIVATE or BANK.");
        }
    }
}
