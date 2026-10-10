package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One MFA recovery code (ten are issued per enrolment). The plaintext is
 * shown exactly once at enrolment; only the password-encoder hash (Argon2id)
 * is stored. UsedAt marks a code as burned — a used code never verifies
 * again.
 *
 * <p>SCHEMA PARITY with V41 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "RecoveryCode")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RecoveryCodeId")
    private int recoveryCodeId;

    @Column(name = "UserId", nullable = false)
    private int userId;

    @Column(name = "CodeHash", length = 255, nullable = false)
    private String codeHash;

    @Column(name = "UsedAt")
    private Instant usedAt;

    public RecoveryCode() {
    }

    public RecoveryCode(int userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public int getRecoveryCodeId() {
        return recoveryCodeId;
    }

    public void setRecoveryCodeId(int recoveryCodeId) {
        this.recoveryCodeId = recoveryCodeId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }
}
