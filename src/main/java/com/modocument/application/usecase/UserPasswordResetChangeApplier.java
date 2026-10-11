package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * USER_PASSWORD_RESET under dual control (phase 2, ws22). SUBMIT runs the
 * refusals as the maker (other bank → 404, self → 409, IdP-owned account →
 * 409), validates the temporary password against the CURRENT password
 * policy and — like USER_CREATE — replaces it with its hash: the persisted
 * payload is {@code {userId, targetEmail, passwordHash}}, never plaintext
 * (and the hash itself is stripped from every API response). APPLY
 * delegates to {@link UserAdminUseCase#applyPasswordReset}, which re-runs
 * the refusals as the checker and performs the session kill + audit.
 */
@Component
public class UserPasswordResetChangeApplier implements PendingChangeApplier {

    private final UserAdminUseCase userAdmin;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public UserPasswordResetChangeApplier(UserAdminUseCase userAdmin,
                                          PasswordPolicy passwordPolicy,
                                          PasswordEncoder passwordEncoder,
                                          ObjectMapper objectMapper) {
        this.userAdmin = userAdmin;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.USER_PASSWORD_RESET;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int userId = ChangePayloads.requireInt(payload, "userId");
        String temporaryPassword = ChangePayloads.requireText(payload, "temporaryPassword", 200);
        AppUser target = userAdmin.requireOtherSameBankUser(userId,
                "You cannot reset your own password; change it in your profile.");
        UserAdminUseCase.requireLocalPasswordAccount(target);
        // The maker learns about a weak password NOW, not the checker later.
        passwordPolicy.validate(temporaryPassword, target.getEmail());

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("userId", userId);
        // For the approvals listing: the checker reviews a person, not an id.
        stored.put("targetEmail", target.getEmail());
        // The ONLY moment the plaintext exists in this flow: hashed here at
        // submit; row, listing and applier all carry the hash alone.
        stored.put("passwordHash", passwordEncoder.encode(temporaryPassword));
        return new Prepared(stored, String.valueOf(userId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        userAdmin.applyPasswordReset(
                ChangePayloads.requireInt(payload, "userId"),
                ChangePayloads.requireText(payload, "passwordHash", 255));
    }
}
