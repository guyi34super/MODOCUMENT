package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * USER_MFA_RESET under dual control (phase 2, ws22): removing a user's
 * second factor is a credential event, so it earns the four-eyes gate.
 * SUBMIT refuses as the maker (other bank → 404, self → 409, nothing
 * enrolled → 409) and persists {@code {userId, targetEmail, reason}}; APPLY
 * delegates to {@link UserAdminUseCase#applyMfaReset}, which re-runs the
 * refusals as the checker, deletes the enrolment + recovery codes, kills
 * every session and writes the USER_MFA_RESET audit row.
 */
@Component
public class UserMfaResetChangeApplier implements PendingChangeApplier {

    private static final int MAX_REASON = 500;

    private final UserAdminUseCase userAdmin;
    private final UserMfaRepository userMfa;
    private final ObjectMapper objectMapper;

    public UserMfaResetChangeApplier(UserAdminUseCase userAdmin, UserMfaRepository userMfa,
                                     ObjectMapper objectMapper) {
        this.userAdmin = userAdmin;
        this.userMfa = userMfa;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.USER_MFA_RESET;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int userId = ChangePayloads.requireInt(payload, "userId");
        String reason = ChangePayloads.requireText(payload, "reason", MAX_REASON).trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("A reason is required.");
        }
        AppUser target = userAdmin.requireOtherSameBankUser(userId,
                "You cannot reset your own MFA.");
        if (userMfa.getByUserId(userId).isEmpty()) {
            throw new IllegalStateException(
                    target.getEmail() + " has no MFA enrolment to reset.");
        }

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("userId", userId);
        stored.put("targetEmail", target.getEmail());
        stored.put("reason", reason);
        return new Prepared(stored, String.valueOf(userId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        userAdmin.applyMfaReset(
                ChangePayloads.requireInt(payload, "userId"),
                ChangePayloads.requireText(payload, "reason", MAX_REASON));
    }
}
