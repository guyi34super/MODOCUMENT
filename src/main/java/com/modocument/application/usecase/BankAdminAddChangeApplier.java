package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.BankDirectory.BankRef;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.application.usecase.PlatformBankLifecycleUseCase.NewBankUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * BANK_ADMIN_ADD under dual control (ws21) — the platform owner's break-glass
 * for a customer bank that has lost its administrators (or, on a fresh
 * install, never had a second one). SUBMIT, as the maker: the bank must
 * exist (404) and be a customer bank (409), the email must be new (409), the
 * temporary password must pass the policy (400) and a reason is required;
 * the password is replaced by its hash, so the persisted payload is
 * {@code {bankId, bankCode, bankName, email, displayName, passwordHash,
 * reason}}. APPLY delegates to
 * {@link PlatformBankLifecycleUseCase#applyAdminAdd}: an ADMIN with
 * MustChangePassword (and, as an ADMIN, forced TOTP enrolment), audited in
 * both banks with the platform maker and checker.
 */
@Component
public class BankAdminAddChangeApplier implements PendingChangeApplier {

    private final PlatformBankLifecycleUseCase lifecycle;
    private final AppUserRepository users;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public BankAdminAddChangeApplier(PlatformBankLifecycleUseCase lifecycle,
                                     AppUserRepository users, PasswordPolicy passwordPolicy,
                                     PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.lifecycle = lifecycle;
        this.users = users;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.BANK_ADMIN_ADD;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int bankId = ChangePayloads.requireInt(payload, "bankId");
        String email = ChangePayloads.requireText(payload, "email", 256).trim()
                .toLowerCase(Locale.ROOT);
        if (!email.contains("@")) {
            throw new IllegalArgumentException("A valid email is required.");
        }
        String displayName = ChangePayloads.optionalText(payload, "displayName", 200);
        String temporaryPassword = ChangePayloads.requireText(payload, "temporaryPassword", 200);
        String reason = ChangePayloads.requireText(payload, "reason", 500).trim();
        BankRef bank = lifecycle.requireCustomerBank(bankId, "Platform administrators are "
                + "added through the platform's own user administration, not break-glass.");
        passwordPolicy.validate(temporaryPassword, email);
        if (users.existsByEmail(email)) {
            throw new IllegalStateException("An account with this email already exists.");
        }

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("bankId", bankId);
        stored.put("bankCode", bank.code());
        stored.put("bankName", bank.name());
        stored.put("email", email);
        if (displayName != null && !displayName.isBlank()) {
            stored.put("displayName", displayName.trim());
        }
        // The ONLY moment the plaintext exists in this flow: hashed here at
        // submit; row, listing and applier all carry the hash alone.
        stored.put("passwordHash", passwordEncoder.encode(temporaryPassword));
        stored.put("reason", reason);
        return new Prepared(stored, String.valueOf(bankId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        int bankId = ChangePayloads.requireInt(payload, "bankId");
        NewBankUser admin = new NewBankUser(
                ChangePayloads.requireText(payload, "email", 256),
                ChangePayloads.optionalText(payload, "displayName", 200),
                Role.ADMIN,
                ChangePayloads.requireText(payload, "passwordHash", 255));
        lifecycle.applyAdminAdd(bankId, admin, ChangePayloads.requireText(payload, "reason", 500),
                change.getBankId(), change.getMakerUserId());
    }
}
