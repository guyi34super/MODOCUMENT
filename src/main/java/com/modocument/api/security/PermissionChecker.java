package com.modocument.api.security;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.security.RolePermissions;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
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
 * <p>Forced password change (ws09): while the caller's MustChangePassword
 * flag stands, every permission except {@link Permission#PROFILE_SELF} is
 * refused regardless of role — self-service (the password change itself,
 * logout, profile, MFA) stays open, everything else answers 403 with code
 * PASSWORD_CHANGE_REQUIRED (see ApiExceptionHandler). The flag probe runs
 * only after the role check granted a non-PROFILE_SELF permission.
 */
@Component("perm")
public class PermissionChecker {

    private final CurrentUserProvider currentUser;
    private final PasswordChangeRequiredGate passwordChangeGate;

    public PermissionChecker(CurrentUserProvider currentUser,
                             PasswordChangeRequiredGate passwordChangeGate) {
        this.currentUser = currentUser;
        this.passwordChangeGate = passwordChangeGate;
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
        if (required != Permission.PROFILE_SELF && passwordChangeGate.callerMustChange()) {
            // A temporary password only buys the password change itself.
            return false;
        }
        return true;
    }
}
