package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One stored-object delete that failed after its rows were deleted (ws29,
 * V290, BUG-12): the retention purge, the DSR erase or a job deletion
 * committed, the store refused the delete even after the in-call retries,
 * so the key waits here until {@code PendingFileDeleteSweeper} succeeds.
 * Holds a store key only, never content. RLS-scoped by BankId. Mirrors
 * V290__ws29_pending_file_delete.sql on the H2 schema.
 */
@Entity
@Table(name = "PendingFileDelete",
        uniqueConstraints = @UniqueConstraint(name = "ux_pendingfiledelete_bank_key",
                columnNames = {"BankId", "StorageKey"}),
        indexes = @Index(name = "ix_pendingfiledelete_bankid_nextattemptat",
                columnList = "BankId, NextAttemptAt"))
public class PendingFileDelete {

    /** {@link #lastError} column width. */
    public static final int LAST_ERROR_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PendingFileDeleteId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "StorageKey", length = 1024, nullable = false)
    private String storageKey;

    @ColumnDefault("0")
    @Column(name = "Attempts", nullable = false)
    private int attempts;

    @Column(name = "LastError", length = LAST_ERROR_MAX)
    private String lastError;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "NextAttemptAt", nullable = false)
    private Instant nextAttemptAt;

    public Long getId() {
        return id;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    /** Truncated to the column width. */
    public void setLastError(String lastError) {
        this.lastError = lastError == null || lastError.length() <= LAST_ERROR_MAX ? lastError
                : lastError.substring(0, LAST_ERROR_MAX);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }
}
