package com.modocument.domain.entity;

import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A banner the platform owner shows to bank users (ws24, V241). Every row
 * is owned by the PLATFORM bank, so row level security hides the table from
 * customer tenants; banks see announcements only through
 * {@code GET /api/announcements/active}, served from {@code AnnouncementCache}.
 *
 * <p>Title and body are plain text — the SPA renders them escaped.
 * {@code AudienceBankCodes} is null for every bank, otherwise a
 * comma-separated list of Bank.Code values.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it mirrors
 * V241__ws24_announcement.sql by hand: the CHECKs below, the columns and
 * the indexes (the Bank foreign key and the index predicate are
 * PostgreSQL-only, a plain int here like AlertLog's).
 */
@Entity
@Table(name = "Announcement", indexes = {
        @Index(name = "ix_announcement_bankid_endsat", columnList = "BankId, EndsAt"),
        @Index(name = "ix_announcement_bankid_createdat", columnList = "BankId, CreatedAt")})
@Check(name = "ck_announcement_kind", constraints = "Kind in ('INFO', 'MAINTENANCE', 'RELEASE')")
@Check(name = "ck_announcement_severity",
        constraints = "Severity in ('INFO', 'WARNING', 'CRITICAL')")
@Check(name = "ck_announcement_window", constraints = "EndsAt > StartsAt")
@Check(name = "ck_announcement_maintenance", constraints = "(Kind = 'MAINTENANCE'"
        + " AND MaintenanceStartsAt IS NOT NULL AND MaintenanceEndsAt IS NOT NULL"
        + " AND MaintenanceEndsAt > MaintenanceStartsAt)"
        + " OR (Kind <> 'MAINTENANCE' AND MaintenanceStartsAt IS NULL AND MaintenanceEndsAt IS NULL)")
public class Announcement {

    /** Longest title the API accepts (the column's own limit). */
    public static final int TITLE_MAX = 200;

    /** Longest body the API accepts (the column is text; this is the policy). */
    public static final int BODY_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AnnouncementId")
    private int announcementId;

    /** The platform bank; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Enumerated(EnumType.STRING)
    @Column(name = "Kind", length = 20, nullable = false)
    private AnnouncementKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "Severity", length = 20, nullable = false)
    private AnnouncementSeverity severity;

    @Column(name = "Title", length = TITLE_MAX, nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Body", nullable = false)
    private String body;

    /** Comma-separated Bank.Code values; null = every bank. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "AudienceBankCodes")
    private String audienceBankCodes;

    @Column(name = "StartsAt", nullable = false)
    private Instant startsAt;

    @Column(name = "EndsAt", nullable = false)
    private Instant endsAt;

    @Column(name = "MaintenanceStartsAt")
    private Instant maintenanceStartsAt;

    @Column(name = "MaintenanceEndsAt")
    private Instant maintenanceEndsAt;

    @Column(name = "ReleaseVersion", length = 64)
    private String releaseVersion;

    @Column(name = "Archived", nullable = false)
    private boolean archived;

    /** Platform admin's AppUserId; null when the system created it. */
    @Column(name = "CreatedBy")
    private Integer createdBy;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "UpdatedBy")
    private Integer updatedBy;

    @Column(name = "UpdatedAt")
    private Instant updatedAt;

    public Announcement() {
    }

    public int getAnnouncementId() {
        return announcementId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public AnnouncementKind getKind() {
        return kind;
    }

    public void setKind(AnnouncementKind kind) {
        this.kind = kind;
    }

    public AnnouncementSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AnnouncementSeverity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getAudienceBankCodes() {
        return audienceBankCodes;
    }

    public void setAudienceBankCodes(String audienceBankCodes) {
        this.audienceBankCodes = audienceBankCodes;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Instant getMaintenanceStartsAt() {
        return maintenanceStartsAt;
    }

    public void setMaintenanceStartsAt(Instant maintenanceStartsAt) {
        this.maintenanceStartsAt = maintenanceStartsAt;
    }

    public Instant getMaintenanceEndsAt() {
        return maintenanceEndsAt;
    }

    public void setMaintenanceEndsAt(Instant maintenanceEndsAt) {
        this.maintenanceEndsAt = maintenanceEndsAt;
    }

    public String getReleaseVersion() {
        return releaseVersion;
    }

    public void setReleaseVersion(String releaseVersion) {
        this.releaseVersion = releaseVersion;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Integer updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
