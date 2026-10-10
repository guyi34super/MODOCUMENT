package com.modocument.infrastructure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user fixed-window limits for the expensive API endpoints (ws17,
 * SEC-007/gate E2) — the {@code modocument.ratelimit.*} block. One window
 * length for all buckets (default 1 hour) and a permit count per bucket; every
 * value is env-overridable (see application.yml). Enforced by
 * {@link ApiRateLimitInterceptor}, which also owns the bucket → route mapping.
 *
 * <p>No role is exempt, ADMIN included: an admin token is exactly as abusable
 * (scripted loop) or stealable as any other, and these limits exist to contain
 * abuse and resource exhaustion, not to ration legitimate work. Operations
 * raise a bucket through its environment variable when a workload genuinely
 * needs more.
 */
@ConfigurationProperties(prefix = "modocument.ratelimit")
public class ApiRateLimitProperties {

    /** Master switch; the limits below only apply when true. */
    private boolean enabled = true;

    /** Window length shared by all buckets (default 1 hour). */
    private long windowMillis = 3_600_000L;

    /** Bound on distinct (bucket, user) counters kept in memory. */
    private int maxKeys = 100_000;

    /** File uploads (standalone upload, doc-creation assets and blueprints): per user/window. */
    private int uploads = 20;

    /** Ingestion job create + resume/remap/revalidate, combined. */
    private int ingestion = 60;

    /** Document-to-table transform runs. */
    private int transform = 30;

    /** Ingestion exports, file and database combined. */
    private int exports = 60;

    /** Doc-creation document generation. */
    private int docGenerate = 30;

    /** Extraction job create (auto-starts by default) + start + bridge. */
    private int extraction = 30;

    /** Data-subject-rights search/export/erase. */
    private int dsr = 10;

    /** Password changes. */
    private int passwordChange = 5;

    /**
     * Authenticated TOTP checks: MFA activation and MFA disable (each
     * verifies a code). Without a cap, a stolen access token could
     * brute-force the code that turns MFA off.
     */
    private int mfaCode = 10;

    /**
     * On-demand runs of the cross-bank background jobs (retention purge, SoD
     * scan): each sweeps every bank, so a scripted loop is a DB-load lever.
     */
    private int adminJobs = 10;

    /**
     * Admin-issued credential resets: temporary-password and MFA resets of
     * another user (phase 2, ws22). Each submits a maker/checker request,
     * so a scripted loop would flood the approval queue and the password
     * hasher; ten per window is far above any real helpdesk load.
     */
    private int adminSecurity = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    public int getUploads() {
        return uploads;
    }

    public void setUploads(int uploads) {
        this.uploads = uploads;
    }

    public int getIngestion() {
        return ingestion;
    }

    public void setIngestion(int ingestion) {
        this.ingestion = ingestion;
    }

    public int getTransform() {
        return transform;
    }

    public void setTransform(int transform) {
        this.transform = transform;
    }

    public int getExports() {
        return exports;
    }

    public void setExports(int exports) {
        this.exports = exports;
    }

    public int getDocGenerate() {
        return docGenerate;
    }

    public void setDocGenerate(int docGenerate) {
        this.docGenerate = docGenerate;
    }

    public int getExtraction() {
        return extraction;
    }

    public void setExtraction(int extraction) {
        this.extraction = extraction;
    }

    public int getDsr() {
        return dsr;
    }

    public void setDsr(int dsr) {
        this.dsr = dsr;
    }

    public int getPasswordChange() {
        return passwordChange;
    }

    public void setPasswordChange(int passwordChange) {
        this.passwordChange = passwordChange;
    }

    public int getMfaCode() {
        return mfaCode;
    }

    public void setMfaCode(int mfaCode) {
        this.mfaCode = mfaCode;
    }

    public int getAdminJobs() {
        return adminJobs;
    }

    public void setAdminJobs(int adminJobs) {
        this.adminJobs = adminJobs;
    }

    public int getAdminSecurity() {
        return adminSecurity;
    }

    public void setAdminSecurity(int adminSecurity) {
        this.adminSecurity = adminSecurity;
    }
}
