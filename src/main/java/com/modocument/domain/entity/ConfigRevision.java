package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * The settings change counter (V200, ws20): a single row (Id = 1) bumped in
 * the same transaction as every settings write. Every application task polls
 * it and reloads its in-memory settings snapshot when the revision moved —
 * cross-task propagation without LISTEN/NOTIFY. Tenant-less (no BankId) by
 * design; NonTenantTableInventoryPgIT pins it.
 *
 * <p>SCHEMA PARITY: kept in sync with V200__ws20_settings.sql by hand (the
 * H2 test schema is built from this entity). On H2 the row is created at
 * startup by {@code SettingsCache}; on PostgreSQL the migration seeds it.
 */
@Entity
@Table(name = "ConfigRevision")
// SCHEMA PARITY with V200 (ck_configrevision_single_row) on H2.
@Check(name = "ck_configrevision_single_row", constraints = "Id = 1")
public class ConfigRevision {

    /** The one row's id. */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "Id")
    private short id;

    @Column(name = "Revision", nullable = false)
    private long revision;

    @Column(name = "UpdatedAt", nullable = false)
    private Instant updatedAt;

    public ConfigRevision() {
    }

    public ConfigRevision(long revision, Instant updatedAt) {
        this.id = SINGLETON_ID;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    public short getId() {
        return id;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
