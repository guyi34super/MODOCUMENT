package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.AuditOutcome;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Server-side invalidation of outstanding access tokens (gate B5): bumping
 * AppUsers.TokenVersion makes the {@code RevocationValidator} reject every
 * token minted with an older {@code token_ver} claim on its very next
 * request. Password change goes through here today; the admin
 * user-management endpoints (ws09) call the same seam on role change or
 * account disable tomorrow — every security-relevant change to an account
 * uses this ONE method so the bump and its audit trail can never be
 * forgotten on a new path.
 */
@Service
public class UserSecurityService {

    private final AppUserRepository users;
    private final AuditRecorder audit;

    public UserSecurityService(AppUserRepository users, AuditRecorder audit) {
        this.users = users;
        this.audit = audit;
    }

    /**
     * Bumps the user's TokenVersion and audits TOKEN_VERSION_BUMPED with the
     * {@code reason} (e.g. "password-change", "role-change") — the audit
     * trail then explains WHY every outstanding token died. Callers that end
     * refresh sessions too (password change does) revoke those separately;
     * the bump only kills access tokens.
     */
    public AppUser bumpTokenVersion(int userId, String reason) {
        AppUser user = users.getById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found."));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedDate(LocalDateTime.now());
        AppUser saved = users.save(user);
        audit.record(AuditEventSpec.builder("TOKEN_VERSION_BUMPED", AuditOutcome.SUCCESS)
                .actorUserId(saved.getAppUserId())
                .actorEmail(saved.getEmail())
                .bankId(saved.getBankId())
                .entity("AppUser", String.valueOf(saved.getAppUserId()))
                .newValues(Map.of("reason", reason, "tokenVersion", saved.getTokenVersion()))
                .build());
        return saved;
    }
}
