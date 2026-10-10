package com.modocument.application.security;

import com.modocument.domain.model.ChangeType;

/**
 * Who decides a maker/checker change (ws10 + phase 2, contracts §2). Every
 * {@link ChangeType} belongs to exactly one audience; the switch in
 * {@link #of} is exhaustive, so a new change type does not compile until it
 * is placed.
 *
 * <ul>
 *   <li>{@link #BANK} — a bank's own change (users, templates, bank
 *       settings, integrations). The row lives in the maker's bank and any
 *       OTHER user of that bank holding {@code CHANGE_APPROVE} decides it
 *       (APPROVER or ADMIN; in the platform bank, another platform
 *       admin).</li>
 *   <li>{@link #PLATFORM} — a platform-owner change (platform settings,
 *       bank lifecycle, egress approvals). The row lives in the platform
 *       bank and only a SECOND platform admin decides it: the checker needs
 *       {@code CHANGE_APPROVE} plus the type's own platform permission,
 *       which {@code PermissionChecker} grants only inside the platform
 *       bank.</li>
 * </ul>
 * Self-approval stays blocked for both by {@link SodPolicy}.
 */
public enum ChangeAudience {

    /** Same-bank approvers. */
    BANK,
    /** A second platform admin, in the platform bank. */
    PLATFORM;

    /** The audience of one change type. */
    public static ChangeAudience of(ChangeType type) {
        return switch (type) {
            case USER_CREATE, USER_ROLE_CHANGE, USER_DISABLE,
                 TEMPLATE_UPDATE, TEMPLATE_DELETE,
                 SETTING_UPDATE, USER_PASSWORD_RESET, USER_MFA_RESET,
                 INTEGRATION_CONNECTION_CHANGE, INTEGRATION_FLOW_ENABLE,
                 API_CLIENT_CREATE, AGENT_REGISTER -> BANK;
            case PLATFORM_SETTING_UPDATE, BANK_CREATE, BANK_SUSPEND, BANK_ADMIN_ADD,
                 EGRESS_RULE_APPROVE -> PLATFORM;
        };
    }
}
