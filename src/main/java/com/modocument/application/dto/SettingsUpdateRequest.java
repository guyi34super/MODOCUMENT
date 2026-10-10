package com.modocument.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/admin/settings and PUT /api/platform/settings (ws20):
 * {@code {changes:[{key, value|null, expectedVersion}], comment}}. Operational
 * changes apply at once; security changes (and every platform bank-bound
 * change) are bundled into ONE maker/checker request carrying
 * {@code comment}.
 */
public record SettingsUpdateRequest(
        @NotEmpty(message = "At least one change is required.")
        @Size(max = 100, message = "At most 100 changes per request.")
        List<@Valid @NotNull Change> changes,
        @Size(max = 500, message = "The comment must be at most 500 characters.")
        String comment) {

    /**
     * One change. {@code value} is JSON-typed (number, boolean, or a string
     * such as {@code "15m"}); JSON {@code null} resets the setting to the
     * inherited value. At the PLATFORM layer only: an ABSENT {@code value}
     * leaves the value as is (for a bounds-only change), and
     * {@code bankMin}/{@code bankMax} set (null: clear) the range banks may
     * choose from. {@code expectedVersion} is the {@code version} the caller
     * read (0 when the setting had no stored row).
     */
    public record Change(
            @NotBlank(message = "A setting key is required.")
            @Size(max = 100, message = "A setting key is at most 100 characters.")
            String key,
            JsonNode value,
            JsonNode bankMin,
            JsonNode bankMax,
            @NotNull(message = "expectedVersion is required (0 for a setting never changed).")
            @PositiveOrZero(message = "expectedVersion cannot be negative.")
            Integer expectedVersion) {
    }
}
