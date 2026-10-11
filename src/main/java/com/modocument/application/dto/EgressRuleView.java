package com.modocument.application.dto;

import com.modocument.domain.model.integration.EgressRuleStatus;

import java.time.Instant;

/**
 * One egress rule (ws25, docs/phase2/contracts.md §7.3 {@code EgressRuleView})
 * as the bank's Integrations console and the platform console show it.
 * The contract fields come first ({@code id, host, cidr, port, status, reason,
 * requestedBy, approvedBy, createdAt}); the rest are additive. Both routes
 * fill the bank fields; the bank's console simply shows its own bank.
 *
 * @param requestedBy     AppUserId of the bank user who asked
 * @param approvedBy      AppUserId of the approving platform admin (null until APPROVED)
 * @param bankId          the requesting bank
 * @param bankCode        its code
 * @param bankName        its name
 * @param pendingChangeId the REQUESTED EGRESS_RULE_APPROVE change awaiting a
 *                        second platform admin (platform console only; null otherwise)
 */
public record EgressRuleView(int id, String host, String cidr, int port, EgressRuleStatus status,
                             String reason, int requestedBy, Integer approvedBy,
                             Instant createdAt, int bankId, String bankCode, String bankName,
                             Instant approvedAt, Integer pendingChangeId) {
}
