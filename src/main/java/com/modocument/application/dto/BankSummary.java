package com.modocument.application.dto;

import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;

import java.time.LocalDateTime;

/**
 * One bank as the platform console sees it (ws21, docs/phase2/contracts.md
 * §3: {@code GET /api/platform/banks[/{id}]}). COUNTS ONLY — identifying
 * columns of the Bank row plus aggregates; never a user's email, a
 * template, a job or any other row content of the bank.
 *
 * @param users            enabled (not disabled) accounts
 * @param mfaEnrolledPct   share of those with an ACTIVE TOTP enrolment,
 *                         0–100 rounded; null when the bank has no users
 * @param jobs7d           ingestion jobs created in the last 7 days
 * @param failures7d       of those, the ones that ended FAILED
 * @param aiShare7d        share of the last 7 days' parses done by the AI
 *                         (0–1); null until parse provenance lands (ws23)
 * @param codeShare7d      same for the code fallback; null until ws23
 * @param lastActivityAt   most recent sign-in of any enabled account, or null
 */
public record BankSummary(int bankId, String code, String name, BankKind kind,
                          BankStatus status, long users, Integer mfaEnrolledPct,
                          long jobs7d, long failures7d, Double aiShare7d, Double codeShare7d,
                          LocalDateTime lastActivityAt) {
}
