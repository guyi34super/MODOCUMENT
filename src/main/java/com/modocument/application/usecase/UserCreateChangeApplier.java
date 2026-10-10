package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * USER_CREATE under dual control (ws10). SUBMIT validates everything the
 * direct endpoint used to (email shape, role vocabulary, password policy,
 * duplicate email) and — the one deliberate transformation — replaces the
 * temporary password with its Argon2id hash, so no password material ever
 * sits in the PendingChange row or in front of the checker. APPLY delegates
 * to {@link UserAdminUseCase#createApprovedUser}, which re-checks the
 * duplicate and writes the USER_CREATED audit row exactly like before.
 */
@Component
public class UserCreateChangeApplier implements PendingChangeApplier {

    private final AppUserRepository users;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final UserAdminUseCase userAdmin;
    private final ObjectMapper objectMapper;

    public UserCreateChangeApplier(AppUserRepository users, PasswordPolicy passwordPolicy,
                                   PasswordEncoder passwordEncoder, UserAdminUseCase userAdmin,
                                   ObjectMapper objectMapper) {
        this.users = users;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.userAdmin = userAdmin;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.USER_CREATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        String email = ChangePayloads.requireText(payload, "email", 256);
        if (!email.contains("@")) {
            throw new IllegalArgumentException("A valid email is required.");
        }
        String displayName = ChangePayloads.optionalText(payload, "displayName", 200);
        Role role = ChangePayloads.parseRole(ChangePayloads.requireText(payload, "role", 20));
        String temporaryPassword = ChangePayloads.requireText(payload, "temporaryPassword", 200);
        // The maker learns about a weak password or a duplicate NOW, not the
        // checker at approval time.
        passwordPolicy.validate(temporaryPassword, email);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized)) {
            throw new IllegalStateException("An account with this email already exists.");
        }

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("email", normalized);
        if (displayName != null && !displayName.isBlank()) {
            stored.put("displayName", displayName.trim());
        }
        stored.put("role", role.name());
        // The ONLY moment the plaintext exists in this flow: hashed here at
        // submit; row, listing and applier all carry the hash alone.
        stored.put("passwordHash", passwordEncoder.encode(temporaryPassword));
        return new Prepared(stored, null);
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        var created = userAdmin.createApprovedUser(
                ChangePayloads.requireText(payload, "email", 256),
                ChangePayloads.optionalText(payload, "displayName", 200),
                ChangePayloads.parseRole(ChangePayloads.requireText(payload, "role", 20)),
                ChangePayloads.requireText(payload, "passwordHash", 255),
                change.getBankId());
        // The target exists only now: stamp its id onto the pending row the
        // use case is about to persist as APPLIED.
        change.setTargetEntityId(String.valueOf(created.userId()));
    }
}
