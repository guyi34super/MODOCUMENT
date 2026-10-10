package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.application.usecase.PlatformBankLifecycleUseCase.NewBankUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BANK_CREATE under dual control (ws21): a platform admin onboards a customer
 * bank with its first users; a SECOND platform admin approves.
 *
 * <p>SUBMIT (as the maker) validates everything — code shape and
 * availability, name, 2..{@value PlatformBankLifecycleUseCase#MAX_ONBOARDING_USERS}
 * users with unique, unused emails, bank roles only
 * ({@code RoleAssignmentPolicy} for a CUSTOMER bank), at least one ADMIN
 * and at least two who can approve (ADMIN or APPROVER — otherwise the new
 * bank could never pass its own maker/checker), and each temporary password
 * against the password policy — and replaces every temporary password with
 * its hash: the persisted payload is
 * {@code {code, name, users:[{email, displayName, role, passwordHash}]}},
 * never plaintext (and the hashes are stripped from every API response).
 *
 * <p>APPLY delegates to {@link PlatformBankLifecycleUseCase#applyOnboarding},
 * which re-checks the code and the emails and creates bank + users in one
 * system transaction.
 */
@Component
public class BankCreateChangeApplier implements PendingChangeApplier {

    private final PlatformBankLifecycleUseCase lifecycle;
    private final AppUserRepository users;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public BankCreateChangeApplier(PlatformBankLifecycleUseCase lifecycle,
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
        return ChangeType.BANK_CREATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        String code = PlatformBankLifecycleUseCase.normalizeCode(
                ChangePayloads.requireText(payload, "code", 40));
        String name = requireName(payload);
        JsonNode list = requireUserArray(payload);

        // Shape first (cheap), then the password policy and hashing (Argon2id).
        List<ParsedUser> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : list) {
            ParsedUser user = parseUser(node, "temporaryPassword", 200);
            if (!seen.add(user.email())) {
                throw new IllegalArgumentException("The email " + user.email()
                        + " appears twice; every first user needs an account of their own.");
            }
            parsed.add(user);
        }
        requireApprovalQuorum(parsed.stream().map(ParsedUser::role).toList());
        for (ParsedUser user : parsed) {
            // The maker learns about a weak password NOW, not the checker later.
            passwordPolicy.validate(user.secret(), user.email());
        }
        for (ParsedUser user : parsed) {
            if (users.existsByEmail(user.email())) {
                throw new IllegalStateException("An account with the email " + user.email()
                        + " already exists.");
            }
        }
        String available = lifecycle.requireAvailableCode(code);

        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("code", available);
        stored.put("name", name);
        ArrayNode storedUsers = stored.putArray("users");
        for (ParsedUser user : parsed) {
            ObjectNode entry = storedUsers.addObject();
            entry.put("email", user.email());
            if (user.displayName() != null) {
                entry.put("displayName", user.displayName());
            }
            entry.put("role", user.role().name());
            // The ONLY moment the plaintext exists in this flow: hashed here
            // at submit; row, listing and applier all carry the hash alone.
            entry.put("passwordHash", passwordEncoder.encode(user.secret()));
        }
        return new Prepared(stored, null);
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        String code = ChangePayloads.requireText(payload, "code", 40);
        String name = requireName(payload);
        List<NewBankUser> firstUsers = new ArrayList<>();
        for (JsonNode node : requireUserArray(payload)) {
            ParsedUser user = parseUser(node, "passwordHash", 255);
            firstUsers.add(new NewBankUser(user.email(), user.displayName(), user.role(),
                    user.secret()));
        }
        // Re-checked at apply too: the row came back from the database.
        requireApprovalQuorum(firstUsers.stream().map(NewBankUser::role).toList());
        var bank = lifecycle.applyOnboarding(code, name, List.copyOf(firstUsers),
                change.getBankId(), change.getMakerUserId());
        // The target exists only now: stamp its id onto the pending row the
        // use case is about to persist as APPLIED.
        change.setTargetEntityId(String.valueOf(bank.bankId()));
    }

    /**
     * At least one ADMIN (someone who can administer users at all) and at
     * least two users who can approve — ADMIN or APPROVER — so the new bank
     * can run its own maker/checker from day one.
     */
    static void requireApprovalQuorum(List<Role> roles) {
        long admins = roles.stream().filter(role -> role == Role.ADMIN).count();
        long approvers = roles.stream()
                .filter(role -> role == Role.ADMIN || role == Role.APPROVER)
                .count();
        if (admins < 1) {
            throw new IllegalArgumentException("Onboarding a bank needs at least one ADMIN "
                    + "among its first users.");
        }
        if (approvers < 2) {
            throw new IllegalArgumentException("Onboarding a bank needs at least two first users "
                    + "who can approve changes (ADMIN or APPROVER): every user change in the "
                    + "bank needs a second approver.");
        }
    }

    private static String requireName(JsonNode payload) {
        String name = ChangePayloads.requireText(payload, "name", 200).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A bank name is required.");
        }
        return name;
    }

    private static JsonNode requireUserArray(JsonNode payload) {
        JsonNode list = payload == null ? null : payload.get("users");
        if (list == null || !list.isArray() || list.size() < 2) {
            throw new IllegalArgumentException("Onboarding a bank needs at least two first users "
                    + "(at least one ADMIN), so the bank can approve its own changes.");
        }
        if (list.size() > PlatformBankLifecycleUseCase.MAX_ONBOARDING_USERS) {
            throw new IllegalArgumentException("Onboard at most "
                    + PlatformBankLifecycleUseCase.MAX_ONBOARDING_USERS + " first users; the "
                    + "bank's own administrators add the rest.");
        }
        return list;
    }

    /** One user entry; {@code secretField} is the temporary password at submit, the hash at apply. */
    private static ParsedUser parseUser(JsonNode node, String secretField, int secretMax) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Each first user must be an object with email, "
                    + "displayName, role and temporaryPassword.");
        }
        String email = ChangePayloads.requireText(node, "email", 256).trim()
                .toLowerCase(Locale.ROOT);
        if (!email.contains("@")) {
            throw new IllegalArgumentException("A valid email is required for every first user.");
        }
        String displayName = ChangePayloads.optionalText(node, "displayName", 200);
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        Role role = ChangePayloads.parseRole(ChangePayloads.requireText(node, "role", 20),
                BankKind.CUSTOMER);
        String secret = ChangePayloads.requireText(node, secretField, secretMax);
        return new ParsedUser(email, displayName, role, secret);
    }

    private record ParsedUser(String email, String displayName, Role role, String secret) {
    }
}
