package com.modocument.application.usecase;

import com.modocument.application.dto.AccessReviewRowDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.AuditOutcome;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quarterly recertification worksheet (ws09, the SEC-013 seam): per user
 * of the CALLER'S bank, everything the reviewing Admin needs to confirm the
 * account should keep its access — role, last login, days since, MFA state,
 * disabled state — plus the {@code inactive90d} flag that marks the prime
 * removal candidates. The role→permission matrix itself is code-pinned
 * (RolePermissionMatrixTest), so "permission drift" reduces to reviewing
 * these role assignments.
 */
@Service
public class AccessReviewUseCase {

    /** No login for this many days flags the account as inactive. */
    static final int INACTIVE_DAYS = 90;

    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final UserMfaRepository userMfa;
    private final AuditRecorder audit;

    public AccessReviewUseCase(CurrentUserProvider currentUser, AppUserRepository users,
                               UserMfaRepository userMfa, AuditRecorder audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.userMfa = userMfa;
        this.audit = audit;
    }

    /** The worksheet for the caller's bank; generating it is itself audited. */
    public List<AccessReviewRowDto> generate() {
        int bankId = currentUser.requireBankId();
        List<AppUser> bankUsers = users.listByBank(bankId);
        Set<Integer> mfaOn = userMfa.enabledUserIds(
                bankUsers.stream().map(AppUser::getAppUserId).toList());
        LocalDateTime now = LocalDateTime.now();
        List<AccessReviewRowDto> rows = bankUsers.stream()
                .map(user -> toRow(user, mfaOn.contains(user.getAppUserId()), now))
                .toList();
        audit.record(AuditEventSpec.builder("ACCESS_REVIEW_GENERATED", AuditOutcome.SUCCESS)
                .entity("AppUserList", String.valueOf(bankId))
                .newValues(Map.of("users", rows.size()))
                .build());
        return rows;
    }

    private static AccessReviewRowDto toRow(AppUser user, boolean mfaEnabled, LocalDateTime now) {
        Long daysSince = user.getLastLoginAt() == null
                ? null
                : Duration.between(user.getLastLoginAt(), now).toDays();
        // Never-logged-in accounts count as inactive once they are older
        // than the window — an unused credential is exactly what a review
        // should question; a freshly provisioned joiner is not flagged.
        boolean inactive = daysSince == null
                ? user.getCreatedDate() == null
                        || user.getCreatedDate().isBefore(now.minusDays(INACTIVE_DAYS))
                : daysSince >= INACTIVE_DAYS;
        return new AccessReviewRowDto(user.getAppUserId(), user.getEmail(),
                user.getDisplayName(), user.getRole() == null ? null : user.getRole().name(),
                user.isDisabled(), mfaEnabled, user.getLastLoginAt(), daysSince, inactive);
    }
}
