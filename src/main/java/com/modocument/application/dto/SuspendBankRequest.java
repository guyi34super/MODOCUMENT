package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of {@code POST /api/platform/banks/{id}/suspend} (ws21,
 * BANK_SUSPEND): why the bank is being suspended. The checker reads it in
 * the approval queue and it lands in the BANK_SUSPENDED audit rows.
 */
public record SuspendBankRequest(@NotBlank @Size(max = 500) String reason) {
}
