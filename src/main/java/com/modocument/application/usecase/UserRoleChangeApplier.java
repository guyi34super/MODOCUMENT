package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Role;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * USER_ROLE_CHANGE under dual control (ws10). SUBMIT re-runs the ws09
 * refusals as the maker (cross-bank target looks like a missing id, no
 * self-targeting); APPLY delegates to {@link UserAdminUseCase#changeRole},
 * which re-runs them all as the checker — including the last-admin guard —
 * and performs the B5 token kill + USER_ROLE_CHANGED audit.
 *
 * <p>The role vocabulary depends on the kind of the bank the change lives
 * in (ws21, {@code RoleAssignmentPolicy}): bank roles in a customer bank,
 * PLATFORM_ADMIN only in the platform bank.
 */
@Component
public class UserRoleChangeApplier implements PendingChangeApplier {

    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final UserAdminUseCase userAdmin;
    private final ObjectMapper objectMapper;
    private final BankDirectory bankDirectory;

    public UserRoleChangeApplier(AppUserRepository users, CurrentUserProvider currentUser,
                                 UserAdminUseCase userAdmin, ObjectMapper objectMapper,
                                 BankDirectory bankDirectory) {
        this.users = users;
        this.currentUser = currentUser;
        this.userAdmin = userAdmin;
        this.objectMapper = objectMapper;
        this.bankDirectory = bankDirectory;
    }

    @Override
    public ChangeType type() {
        return ChangeType.USER_ROLE_CHANGE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int targetUserId = ChangePayloads.requireInt(payload, "targetUserId");
        // The target must live in the maker's bank (checked next), so the
        // maker's bank decides the vocabulary.
        Role role = ChangePayloads.parseRole(ChangePayloads.requireText(payload, "role", 20),
                bankDirectory.kindOf(currentUser.requireBankId()));
        AppUser target = requireSameBankUser(targetUserId);
        if (targetUserId == currentUser.requireUserId()) {
            throw new IllegalStateException("You cannot change your own role.");
        }

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("targetUserId", targetUserId);
        // For the approvals listing: the checker reviews a person, not an id.
        stored.put("targetEmail", target.getEmail());
        stored.put("role", role.name());
        return new Prepared(stored, String.valueOf(targetUserId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        userAdmin.changeRole(
                ChangePayloads.requireInt(payload, "targetUserId"),
                ChangePayloads.parseRole(ChangePayloads.requireText(payload, "role", 20),
                        bankDirectory.kindOf(change.getBankId())));
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
