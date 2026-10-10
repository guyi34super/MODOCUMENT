package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/admin/dashboard (phase 2, ws22): the bank admin's one-screen
 * overview of THEIR bank. Counts and timestamps only — no row content, no
 * emails or names (the bank's own code/name is the only label). Every
 * {@code byStatus}/{@code bySeverity}/{@code byRole} map lists its whole
 * vocabulary with zeros, so the UI renders stable tiles.
 *
 * <p>{@code settings.clamped} (ws20) and {@code version.deployedAt} (ws24)
 * stay zero/null until their data is wired; {@code ingestion.parsedBy}
 * (ws23) and {@code integrations} (ws25/ws26) are live counts.
 *
 * @param generatedAt when these numbers were computed (cached up to 30 s)
 */
public record AdminDashboardDto(Instant generatedAt, BankInfo bank, Users users,
                                Approvals approvals, Ingestion ingestion,
                                Extraction extraction, Alerts alerts, Retention retention,
                                Templates templates, Settings settings, Version version,
                                Integrations integrations) {

    public record BankInfo(String code, String name) {
    }

    /**
     * @param active             not disabled
     * @param locked             a login lockout is in force right now
     * @param mustChangePassword a temporary password is still outstanding
     * @param byRole             every bank role, all accounts
     * @param mfaEnrolled        accounts with an ACTIVATED TOTP enrolment
     * @param adminsWithoutMfa   enabled ADMIN accounts without one
     * @param activeLast7d       signed in during the last 7 days
     */
    public record Users(long total, long active, long disabled, long locked,
                        long mustChangePassword, Map<String, Long> byRole, long mfaEnrolled,
                        long adminsWithoutMfa, long activeLast7d) {
    }

    /**
     * @param pendingChanges  maker/checker changes waiting for a decision
     * @param pendingReleases generated documents submitted for release
     */
    public record Approvals(long pendingChanges, long pendingReleases) {
    }

    public record StatusCounts(Map<String, Long> byStatus) {
    }

    /**
     * @param today      jobs created since local midnight, per status
     * @param last7d     jobs created in the last 7 days, per status
     * @param parsedBy   AI / CODE / AI_PARTIAL parse provenance (ws23) of the
     *                   last 7 days' jobs that were parsed
     * @param failures7d jobs of the last 7 days that ended FAILED
     */
    public record Ingestion(StatusCounts today, StatusCounts last7d,
                            Map<String, Long> parsedBy, long failures7d) {
    }

    public record Extraction(StatusCounts last7d) {
    }

    /** @param lastAt when the bank's newest alert was raised, or null */
    public record Alerts(Map<String, Long> last7dBySeverity, Instant lastAt) {
    }

    /**
     * @param enabled    the daily scheduled purge is on
     * @param days       customer-data retention period
     * @param lastRunAt  the bank's newest RETENTION_PURGE_COMPLETED audit event
     * @param lastPurged items that run purged (sum of its category counts)
     */
    public record Retention(boolean enabled, int days, Instant lastRunAt, Long lastPurged) {
    }

    /** {@code private} is a Java keyword, hence the explicit JSON names. */
    public record Templates(@JsonProperty("private") long privateTemplates,
                            @JsonProperty("shared") long sharedTemplates) {
    }

    /**
     * @param pendingSecurityChanges SETTING_UPDATE changes awaiting approval
     * @param clamped                bank settings clamped by platform bounds (ws20)
     */
    public record Settings(long pendingSecurityChanges, long clamped) {
    }

    public record Version(String version, String commit, Instant deployedAt) {
    }

    /** Runs that finished SUCCEEDED ({@code ok}) / FAILED. A null counter reads "not tracked". */
    public record Runs(Long ok, Long failed) {
    }

    /**
     * Integration hub tiles (ws25/ws26, contracts §3 SE3). The counters are
     * boxed so a null still means "not tracked" (the UI shows "—", unlike a
     * real zero).
     *
     * @param runs24h       integration runs that finished in the last 24 h, SUCCEEDED / FAILED
     * @param deadLetters   outbound deliveries in DEAD status
     * @param agentsOffline enrolled connector agents reading OFFLINE (no heartbeat within
     *                      {@code integration.agentHeartbeatTimeout})
     * @param apiCalls24h   Integration API v1 calls by the bank's clients, last 24 h
     *                      ({@code INTEGRATION_API_CALL} audit rows)
     */
    public record Integrations(Runs runs24h, Long deadLetters, Long agentsOffline,
                               Long apiCalls24h) {
    }
}
