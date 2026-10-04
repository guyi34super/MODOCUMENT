package com.modocument.domain.model;

/**
 * The critical operations under maker/checker dual control (ws10, SEC-023).
 * Each type names the permission the MAKER must hold to request it — the
 * same permission the direct operation used to require — and the entity
 * type the change targets. The CHECKER side is uniform: approving or
 * rejecting any type requires {@link Permission#CHANGE_APPROVE}.
 *
 * <p>Deliberately NOT here: USER_ENABLE. Re-enabling an account only
 * restores access that two users already approved granting, so it stays a
 * direct (single-admin) operation — the lower-risk direction.
 */
public enum ChangeType {

    /** Create a user in the maker's bank (the payload carries a password HASH, never plaintext). */
    USER_CREATE(Permission.USER_ADMIN, "AppUser"),
    /** Change a user's role (mover — applying kills the target's tokens). */
    USER_ROLE_CHANGE(Permission.USER_ADMIN, "AppUser"),
    /** Disable a user (leaver switch — applying makes the account inert). */
    USER_DISABLE(Permission.USER_ADMIN, "AppUser"),
    /** Rename/redescribe a template the maker owns. */
    TEMPLATE_UPDATE(Permission.TEMPLATE_WRITE, "Template"),
    /** Delete a template the maker owns (with its fields and rules). */
    TEMPLATE_DELETE(Permission.TEMPLATE_WRITE, "Template");

    private final Permission makerPermission;
    private final String targetEntityType;

    ChangeType(Permission makerPermission, String targetEntityType) {
        this.makerPermission = makerPermission;
        this.targetEntityType = targetEntityType;
    }

    /** The permission the maker must hold to request this change. */
    public Permission makerPermission() {
        return makerPermission;
    }

    /** The entity type a change of this kind targets (audit/EntityType vocabulary). */
    public String targetEntityType() {
        return targetEntityType;
    }
}
