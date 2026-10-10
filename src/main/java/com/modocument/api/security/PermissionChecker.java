package com.modocument.api.security;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.security.RolePermissions;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code @perm} bean referenced from {@code @RequiresPermission}'s
 * {@code @PreAuthorize("@perm.has('X')")}. Resolves the caller's {@link Role}
 * from the validated JWT's roles claim (via {@link CurrentUserProvider}) and
 * answers from the static {@link RolePermissions} matrix.
 *
 * <p>A token without a usable roles claim (stale pre-RBAC token, foreign
 * token, misconfigured test fixture) answers {@code false} — the caller gets
 * a 403, never a 500: an unidentifiable role must deny, not crash.
 *
 * <p>Platform guard (ws21): a {@link RolePermissions#PLATFORM_PERMISSIONS
 * platform permission}, and ANY permission held through the
 * {@link Role#PLATFORM_ADMIN} role, additionally requires the token's bank
 * to be the platform bank ({@link PlatformBankLookup}). The role matrix
 * alone would trust the roles claim; this pins platform power to the one
 * bank where the V210 trigger lets PLATFORM_ADMIN users exist, so a
 * PLATFORM_ADMIN claim minted for any other bank (a bug, a restored backup,
 * a hand-edited row) is refused. No platform bank yet means no platform
 * permission for anyone.
 *
 * <p>Forced password change (ws09): while the caller's MustChangePassword
 * flag stands, every permission except {@link Permission#PROFILE_SELF} is
 * refused regardless of role — self-service (the password change itself,
 * logout, profile, MFA) stays open, everything else answers 403 with code
 * PASSWORD_CHANGE_REQUIRED (see ApiExceptionHandler). The flag probe runs
 * only after the role check granted a non-PROFILE_SELF permission.
 */
@Component("perm")
public class PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

    private final CurrentUserProvider currentUser;
    private final PasswordChangeRequiredGate passwordChangeGate;
    private final PlatformBankLookup platformBank;

    public PermissionChecker(CurrentUserProvider currentUser,
                             PasswordChangeRequiredGate passwordChangeGate,
                             PlatformBankLookup platformBank) {
        this.currentUser = currentUser;
        this.passwordChangeGate = passwordChangeGate;
        this.platformBank = platformBank;
    }

    /**
     * @param permission a {@link Permission} constant name (never user input —
     *     it comes from the annotation's template substitution)
     */
    public boolean has(String permission) {
        Permission required = Permission.valueOf(permission);
        Role role;
        try {
            role = currentUser.role();
        } catch (IllegalStateException missingOrUnknownRole) {
            // No authenticated JWT, no roles claim, or an unknown role name:
            // deny-by-default (403), never an error page.
            return false;
        }
        if (!RolePermissions.isGranted(role, required)) {
            return false;
        }
        if ((role == Role.PLATFORM_ADMIN
                || RolePermissions.PLATFORM_PERMISSIONS.contains(required))
                && !callerInPlatformBank()) {
            return false;
        }
        if (role == Role.API_CLIENT && !clientScopesGrant(required)) {
            return false;
        }
        if (required != Permission.PROFILE_SELF && passwordChangeGate.callerMustChange()) {
            // A temporary password only buys the password change itself.
            return false;
        }
        return true;
    }

    /**
     * Machine-client scope hook (contracts §1: granted = role grant ∩ the
     * token's {@code scope} claim). TODO(ws26, wave 3): read the client
     * token's scopes here. Until then no API_CLIENT token is issued, and one
     * that somehow appears gets nothing — deny by default.
     */
    private boolean clientScopesGrant(Permission required) {
        return false;
    }

    /** Fails closed: no bank claim, no platform bank, or a lookup error all deny. */
    private boolean callerInPlatformBank() {
        try {
            return platformBank.isPlatformBank(currentUser.requireBankId());
        } catch (IllegalStateException e) {
            log.warn("Platform permission refused: the caller's bank could not be verified "
                    + "as the platform bank ({}).", e.getMessage());
            return false;
        }
    }
}
