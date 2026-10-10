package com.modocument.application.usecase;

import com.modocument.application.dto.AdminUserDto;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Bank-scoped user administration (ws09, gates B1/B10, PRIV-017): a bank
 * Admin manages ONLY the users of their own bank. AppUsers is deliberately
 * not under RLS, so the bank scoping lives here — every operation resolves
 * the caller's bank from the JWT ({@link CurrentUserProvider#requireBankId})
 * and refuses to even acknowledge users of other banks (404, like a
 * non-existent id, so ids cannot be probed across banks).
 *
 * <p>Mover/leaver semantics (the B5 mechanism): a role change or a disable
 * bumps the target's TokenVersion — the {@code RevocationValidator} rejects
 * every outstanding access token on its next request — and revokes every
 * refresh-token family, so the old privileges (or the whole session) die
 * immediately rather than at token expiry.
 *
 * <p>Dual control (ws10, gate B7/SEC-023): user creation, role changes and
 * the disable switch are no longer invoked directly by the requesting
 * admin. The endpoints SUBMIT a PendingChange; the methods here are the
 * APPLIERS a different user's approval executes — and they re-run every
 * invariant (bank scoping, self-targeting, last-admin guard), because the
 * world may have changed between submit and approve. Only re-enabling an
 * account ({@link #setDisabled} with {@code false}) stays direct.
 */
@Service
public class UserAdminUseCase {

    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final UserMfaRepository userMfa;
    private final RefreshTokenRepository refreshTokens;
    private final BankJpaRepository banks;
    private final AuthUseCase auth;
    private final AuditRecorder audit;

    public UserAdminUseCase(CurrentUserProvider currentUser, AppUserRepository users,
                            UserMfaRepository userMfa, RefreshTokenRepository refreshTokens,
                            BankJpaRepository banks, AuthUseCase auth, AuditRecorder audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.userMfa = userMfa;
        this.refreshTokens = refreshTokens;
        this.banks = banks;
        this.auth = auth;
        this.audit = audit;
    }

    /** Every user of the caller's bank, ordered by email (gate B1). */
    public List<AdminUserDto> listUsers() {
        return listBankUsers().stream()
                .map(entry -> AdminUserDto.from(entry.user(), entry.mfaEnabled()))
                .toList();
    }

    /**
     * Creates a user from an APPROVED pending change (ws10 maker/checker:
     * the USER_CREATE applier is the only caller — direct creation without
     * a second administrator's approval no longer exists). The temporary
     * password went through the policy and was hashed when the maker
     * submitted; MustChangePassword is set, so the joiner signs in once and
     * is forced through a password change before anything else. No password
     * material reaches the audit trail.
     */
    @Transactional
    public UserDto createApprovedUser(String email, String displayName, Role role,
                                      String passwordHash, int bankId) {
        requireBankRole(role);
        UserDto created = auth.createUserWithHash(email, passwordHash, displayName, bankId,
                role, true);
        audit.record(AuditEventSpec.builder("USER_CREATED", AuditOutcome.SUCCESS)
                .entity("AppUser", String.valueOf(created.userId()))
                .newValues(Map.of(
                        "email", created.email(),
                        "displayName", created.displayName(),
                        "role", created.role(),
                        "mustChangePassword", true))
                .build());
        return created;
    }

    /**
     * Mover: changes the role and kills every outstanding token of the user
     * (TokenVersion bump + refresh-family revocation) so the old privileges
     * are gone on the target's very next request.
     */
    @Transactional
    public AdminUserDto changeRole(int targetUserId, Role newRole) {
        if (newRole == null) {
            throw new IllegalArgumentException("A role is required.");
        }
        requireBankRole(newRole);
        AppUser target = requireSameBankUser(targetUserId);
        if (targetUserId == currentUser.requireUserId()) {
            // Also one half of the last-admin protection: an admin cannot
            // demote themself into a bank without administrators.
            throw new IllegalStateException("You cannot change your own role.");
        }
        Role oldRole = target.getRole();
        if (oldRole == newRole) {
            return AdminUserDto.from(target, userMfa.isEnabled(targetUserId));
        }
        if (oldRole == Role.ADMIN && !target.isDisabled()
                && users.countActiveAdmins(target.getBankId()) <= 1) {
            throw new IllegalStateException(
                    "Cannot demote the only remaining active administrator of this bank; "
                            + "promote another administrator first.");
        }
        target.setRole(newRole);
        // THE mover mechanism: outstanding access tokens carry the old role
        // in their claims; the version bump makes the RevocationValidator
        // reject them all on their next request, and the refresh revocation
        // stops the session from being silently re-minted.
        target.setTokenVersion(target.getTokenVersion() + 1);
        target.setUpdatedDate(LocalDateTime.now());
        AppUser saved = users.save(target);
        refreshTokens.revokeAllForUser(targetUserId, Instant.now());
        audit.record(AuditEventSpec.builder("USER_ROLE_CHANGED", AuditOutcome.SUCCESS)
                .entity("AppUser", String.valueOf(targetUserId))
                .oldValues(Map.of("role", oldRole == null ? "" : oldRole.name()))
                .newValues(Map.of("role", newRole.name()))
                .build());
        return AdminUserDto.from(saved, userMfa.isEnabled(targetUserId));
    }

    /**
     * The leaver switch (PRIV-017): disabling sets the flag, bumps
     * TokenVersion and revokes every refresh token, so the account is inert
     * the same moment — the existing access token dies at the
     * RevocationValidator on its next request, refresh dies here and at the
     * revoked families, and login answers the generic 401. Re-enabling only
     * clears the flag; the user signs in afresh.
     */
    @Transactional
    public AdminUserDto setDisabled(int targetUserId, boolean disabled) {
        AppUser target = requireSameBankUser(targetUserId);
        if (disabled && targetUserId == currentUser.requireUserId()) {
            throw new IllegalStateException("You cannot disable your own account.");
        }
        if (target.isDisabled() == disabled) {
            return AdminUserDto.from(target, userMfa.isEnabled(targetUserId));
        }
        if (disabled && target.getRole() == Role.ADMIN
                && users.countActiveAdmins(target.getBankId()) <= 1) {
            throw new IllegalStateException(
                    "Cannot disable the only remaining active administrator of this bank; "
                            + "promote another administrator first.");
        }
        target.setDisabled(disabled);
        target.setUpdatedDate(LocalDateTime.now());
        if (disabled) {
            target.setTokenVersion(target.getTokenVersion() + 1);
        }
        AppUser saved = users.save(target);
        if (disabled) {
            refreshTokens.revokeAllForUser(targetUserId, Instant.now());
        }
        audit.record(AuditEventSpec
                .builder(disabled ? "USER_DISABLED" : "USER_ENABLED", AuditOutcome.SUCCESS)
                .entity("AppUser", String.valueOf(targetUserId))
                .oldValues(Map.of("disabled", !disabled))
                .newValues(Map.of("disabled", disabled, "email", saved.getEmail()))
                .build());
        return AdminUserDto.from(saved, userMfa.isEnabled(targetUserId));
    }

    /**
     * Gate B10: the caller's bank's user list, with last-login dates, as a
     * CSV download. Every cell goes through the same formula neutralization
     * as the data exports (a display name like {@code =HYPERLINK(...)} must
     * not execute when the file opens in a spreadsheet).
     */
    public CsvExport exportUsers() {
        int bankId = currentUser.requireBankId();
        List<BankUser> rows = listBankUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("Email", "DisplayName", "Role", "Disabled", "LastLoginAt",
                        "MfaEnabled", "AuthProvider", "CreatedDate")
                .build();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
            for (BankUser row : rows) {
                AppUser user = row.user();
                printer.printRecord(
                        FileExportService.neutralizeFormula(user.getEmail()),
                        FileExportService.neutralizeFormula(user.getDisplayName()),
                        user.getRole() == null ? "" : user.getRole().name(),
                        user.isDisabled(),
                        user.getLastLoginAt() == null ? "" : user.getLastLoginAt().toString(),
                        row.mfaEnabled(),
                        FileExportService.neutralizeFormula(
                                user.getAuthProvider() == null ? "local" : user.getAuthProvider()),
                        user.getCreatedDate() == null ? "" : user.getCreatedDate().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize the user-list export.", e);
        }
        String bankCode = banks.findById(bankId).map(Bank::getCode).orElse("bank" + bankId);
        String fileName = "users-" + safeFileToken(bankCode) + "-" + LocalDate.now() + ".csv";
        audit.record(AuditEventSpec.builder("USER_LIST_EXPORTED", AuditOutcome.SUCCESS)
                .entity("AppUserList", bankCode)
                .newValues(Map.of("rows", rows.size(), "fileName", fileName))
                .build());
        return new CsvExport(fileName, out.toByteArray());
    }

    /** The caller's-bank users joined with their MFA state, in one MFA query. */
    private List<BankUser> listBankUsers() {
        int bankId = currentUser.requireBankId();
        List<AppUser> bankUsers = users.listByBank(bankId);
        Set<Integer> mfaOn = userMfa.enabledUserIds(
                bankUsers.stream().map(AppUser::getAppUserId).toList());
        return bankUsers.stream()
                .map(user -> new BankUser(user, mfaOn.contains(user.getAppUserId())))
                .toList();
    }

    /**
     * Loads the target ONLY when it belongs to the caller's bank. A user of
     * another bank answers exactly like a non-existent id (404) — gate B6:
     * cross-bank administration is out of scope, and the response must not
     * reveal that the id exists elsewhere.
     */
    private AppUser requireSameBankUser(int targetUserId) {
        int bankId = currentUser.requireBankId();
        return users.getById(targetUserId)
                .filter(user -> user.getBankId() == bankId)
                .orElseThrow(() -> new NoSuchElementException(
                        "User " + targetUserId + " not found."));
    }

    /** Only characters that are safe in a Content-Disposition filename. */
    private static String safeFileToken(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "-");
        return cleaned.isBlank() ? "bank" : cleaned;
    }

    /** A ready-to-download CSV: its filename and its UTF-8 bytes. */
    public record CsvExport(String fileName, byte[] content) {
    }

    private record BankUser(AppUser user, boolean mfaEnabled) {
    }

    /** Bank user administration only ever hands out the four bank roles. */
    private static void requireBankRole(Role role) {
        if (role != null && !role.isBankRole()) {
            throw new IllegalArgumentException(
                    "Unknown role '" + role + "'; use ADMIN, APPROVER, READ_WRITE or READ_ONLY.");
        }
    }
}
