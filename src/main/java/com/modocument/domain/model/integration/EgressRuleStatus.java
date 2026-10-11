package com.modocument.domain.model.integration;

/**
 * Lifecycle of a {@code BankEgressRule} (ws25, V251). Stored by NAME.
 *
 * <pre>
 * PENDING ──(EGRESS_RULE_APPROVE, two platform admins)──▶ APPROVED
 *    └────────────────(bank revokes, direct)────────────────▶ REVOKED
 * </pre>
 *
 * Only APPROVED rules open a private destination to the bank's DIRECT
 * connections; revoking (in either state) is immediate — the lower-risk
 * direction.
 */
public enum EgressRuleStatus {
    PENDING,
    APPROVED,
    REVOKED
}
