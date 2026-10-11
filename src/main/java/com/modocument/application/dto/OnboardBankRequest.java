package com.modocument.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * JSON body of {@code POST /api/platform/banks} (ws21, BANK_CREATE): the new
 * customer bank and its first users. The constraints here are schema caps
 * only; the real rules — at least two users, at least one ADMIN and at
 * least two who can approve (ADMIN or APPROVER), bank roles only, the
 * password policy, unique code and emails — run when the change is
 * submitted (BankCreateChangeApplier), so the generic change endpoint gets
 * exactly the same checks. Temporary passwords are hashed at submit and
 * never stored or shown again.
 */
public record OnboardBankRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull @Size(max = 20) List<@Valid @NotNull User> users) {

    /** One first user of the bank; forced to change the password at first sign-in. */
    public record User(
            @NotBlank @Size(max = 256) String email,
            @Size(max = 200) String displayName,
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 200) String temporaryPassword) {
    }
}
