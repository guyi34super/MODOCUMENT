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
    TEMPLATE_DELETE(Permission.TEMPLATE_WRITE, "Template"),

    // ---- Phase 2 (each type's applier lands with its workstream) -----------
    /** Security-tier bank setting changes (ws20). */
    SETTING_UPDATE(Permission.SETTINGS_WRITE, "Setting"),
    /** Platform defaults or bank bounds (ws20). */
    PLATFORM_SETTING_UPDATE(Permission.PLATFORM_SETTINGS, "Setting"),
    /** Set a temporary password, force a change, kill sessions (ws22). */
    USER_PASSWORD_RESET(Permission.USER_ADMIN, "AppUser"),
    /** Remove a user's MFA enrolment so they re-enrol (ws22). */
    USER_MFA_RESET(Permission.USER_ADMIN, "AppUser"),
    /** Onboard a bank with at least two first users (ws21). */
    BANK_CREATE(Permission.PLATFORM_BANKS, "Bank"),
    /** Suspend a bank: every token of its users dies (ws21). */
    BANK_SUSPEND(Permission.PLATFORM_BANKS, "Bank"),
    /** Break-glass: add an admin to a bank (ws21). */
    BANK_ADMIN_ADD(Permission.PLATFORM_BANKS, "Bank"),
    /** New integration connection, or a host/credential change (ws25). */
    INTEGRATION_CONNECTION_CHANGE(Permission.INTEGRATION_ADMIN, "IntegrationConnection"),
    /** Enable or widen an OUTBOUND flow — data leaving the tenant (ws25). */
    INTEGRATION_FLOW_ENABLE(Permission.INTEGRATION_ADMIN, "IntegrationFlow"),
    /** Create an API client for a bank system (ws26). */
    API_CLIENT_CREATE(Permission.INTEGRATION_ADMIN, "ApiClient"),
    /** Register an on-prem Connector Agent (ws26). */
    AGENT_REGISTER(Permission.INTEGRATION_ADMIN, "IntegrationAgent"),
    /** Approve a bank's private-network egress destination (ws25, platform). */
    EGRESS_RULE_APPROVE(Permission.PLATFORM_BANKS, "BankEgressRule");

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
