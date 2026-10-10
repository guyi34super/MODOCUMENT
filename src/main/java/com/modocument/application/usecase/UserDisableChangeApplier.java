package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * USER_DISABLE under dual control (ws10): only the DISABLE direction goes
 * through maker/checker — the leaver switch removes access, so it earns the
 * four-eyes gate; re-enabling restores what was already dual-approved and
 * stays a direct ws09 operation. APPLY delegates to
 * {@link UserAdminUseCase#setDisabled}, which re-runs the self/last-admin
 * guards and performs the PRIV-017 token kill + USER_DISABLED audit.
 */
@Component
public class UserDisableChangeApplier implements PendingChangeApplier {

    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final UserAdminUseCase userAdmin;
    private final ObjectMapper objectMapper;

    public UserDisableChangeApplier(AppUserRepository users, CurrentUserProvider currentUser,
                                    UserAdminUseCase userAdmin, ObjectMapper objectMapper) {
        this.users = users;
        this.currentUser = currentUser;
        this.userAdmin = userAdmin;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.USER_DISABLE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int targetUserId = ChangePayloads.requireInt(payload, "targetUserId");
        AppUser target = requireSameBankUser(targetUserId);
        if (targetUserId == currentUser.requireUserId()) {
            throw new IllegalStateException("You cannot disable your own account.");
        }

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("targetUserId", targetUserId);
        stored.put("targetEmail", target.getEmail());
        return new Prepared(stored, String.valueOf(targetUserId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        userAdmin.setDisabled(ChangePayloads.requireInt(payload, "targetUserId"), true);
    }

    /** Gate B6 at submit: a user of another bank answers like a missing id. */
    private AppUser requireSameBankUser(int targetUserId) {
        int bankId = currentUser.requireBankId();
        return users.getById(targetUserId)
                .filter(user -> user.getBankId() == bankId)
                .orElseThrow(() -> new NoSuchElementException(
                        "User " + targetUserId + " not found."));
    }
}
