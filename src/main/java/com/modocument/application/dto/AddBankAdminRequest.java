package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of {@code POST /api/platform/banks/{id}/admins} (ws21,
 * BANK_ADMIN_ADD — break-glass): a new ADMIN for a customer bank that lost
 * its administrators. The temporary password goes through the password
 * policy and is hashed at submit; the reason is mandatory (it is the
 * break-glass justification the checker approves and the audit records).
 */
public record AddBankAdminRequest(
        @NotBlank @Size(max = 256) String email,
        @Size(max = 200) String displayName,
        @NotBlank @Size(max = 200) String temporaryPassword,
        @NotBlank @Size(max = 500) String reason) {
}
