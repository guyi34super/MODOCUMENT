package com.modocument.domain.entity;

import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A tenant bank. Every AppUser and every tenant-owned row carries a BankId;
 * pre-multi-tenancy data lives in the deterministic default bank seeded by
 * V10__bank_and_user_security_columns.sql.
 *
 * <p>Phase 2 (ws21, V210): {@link #getKind() Kind} tells customer banks from
 * the single platform-owner bank, {@link #getStatus() Status} and
 * {@link #getSuspendedAt() SuspendedAt} carry suspension. SCHEMA PARITY with
 * V210 on the H2 test schema: the two vocabulary CHECKs are mirrored below;
 * the "exactly one PLATFORM bank" partial unique index and the role/kind
 * triggers are PostgreSQL-only (proven by PlatformRoleTriggerPgIT).
 */
@Entity
@Table(name = "Bank")
@Check(name = "ck_bank_kind", constraints = "Kind in ('CUSTOMER', 'PLATFORM')")
@Check(name = "ck_bank_status", constraints = "Status in ('ACTIVE', 'SUSPENDED')")
public class Bank {

    /** Code of the bank seeded by V10 that all pre-existing data belongs to. */
    public static final String DEFAULT_BANK_CODE = "DEFAULT";

    /** Id of that seeded default bank (V10 inserts it with an explicit id). */
    public static final int DEFAULT_BANK_ID = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BankId")
    private int bankId;

    @Column(name = "Code", length = 40, nullable = false, unique = true)
    private String code;

    @Column(name = "Name", length = 200, nullable = false)
    private String name;

    @Column(name = "CreatedDate", nullable = false)
    private LocalDateTime createdDate;

    /** CUSTOMER (default) or the single PLATFORM bank; immutable once set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "Kind", length = 20, nullable = false)
    private BankKind kind = BankKind.CUSTOMER;

    /** ACTIVE (default) or SUSPENDED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20, nullable = false)
    private BankStatus status = BankStatus.ACTIVE;

    /** When the bank was last suspended; NULL while it never was / is active. */
    @Column(name = "SuspendedAt")
    private Instant suspendedAt;

    public Bank() {
    }

    public Bank(String code, String name) {
        this.code = code;
        this.name = name;
        this.createdDate = LocalDateTime.now();
    }

    public Bank(String code, String name, BankKind kind) {
        this(code, name);
        this.kind = kind;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public BankKind getKind() {
        return kind;
    }

    public void setKind(BankKind kind) {
        this.kind = kind;
    }

    public BankStatus getStatus() {
        return status;
    }

    public void setStatus(BankStatus status) {
        this.status = status;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }
}
