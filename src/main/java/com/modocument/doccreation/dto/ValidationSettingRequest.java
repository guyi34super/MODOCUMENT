package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * JSON body of {@code PUT /api/doccreation/blueprints/{id}/validation-setting}
 * and {@code PUT /api/doccreation/instances/{id}/validation-setting}
 * (data-templates-contract §8): validation on or off.
 */
public record ValidationSettingRequest(@NotNull Boolean enabled) {
}
