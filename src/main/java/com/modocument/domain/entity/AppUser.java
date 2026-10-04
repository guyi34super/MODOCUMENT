package com.modocument.domain.entity;

import com.modocument.domain.model.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** An application user account for JWT authentication. */
@Entity
@Table(name = "AppUsers")
// SCHEMA PARITY with V30 (ck_appusers_role) on the H2 test schema: the Role
// vocabulary is closed — see com.modocument.domain.model.Role.
@Check(name = "ck_appusers_role",
        constraints = "Role in ('ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY')")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AppUserId")
    private int appUserId;

    /** The bank this user belongs to (V10); carried in the JWT bank_id claim. */
    @Column(name = "BankId", nullable = false)
    private int bankId = Bank.DEFAULT_BANK_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "Role", length = 20, nullable = false)
    private Role role = Role.READ_ONLY;

    /**
     * Bumped on password change (and later on role change); access tokens
     * whose token_ver claim is older are rejected by the RevocationValidator.
     */
    @Column(name = "TokenVersion", nullable = false)
    private int tokenVersion;

    @Column(name = "LastLoginAt")
    private LocalDateTime lastLoginAt;

    /**
     * Consecutive wrong-password attempts since the last successful login or
     * lock (V40); the attempt after the configured allowance locks the
     * account.
     */
    @Column(name = "FailedLoginCount", nullable = false)
    private int failedLoginCount;

    /** While set and in the future every login attempt gets the generic 401. */
    @Column(name = "LockedUntil")
    private LocalDateTime lockedUntil;

    /**
     * Lockouts since the last successful login; each one doubles the next
     * lock duration (capped — see LoginLockoutPolicy).
     */
    @Column(name = "LockoutCount", nullable = false)
    private int lockoutCount;

    @Column(name = "Email", length = 256, nullable = false, unique = true)
    private String email;

    /**
     * DelegatingPasswordEncoder output: "{argon2}..." (~105 chars) for new
     * hashes, legacy raw BCrypt (60 chars) until the next successful login
     * re-hashes it (SEC-018). Width matches V40.
     */
    @Column(name = "PasswordHash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "DisplayName", length = 200)
    private String displayName;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "SettingsJson")
    private String settingsJson;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    public AppUser() {
    }

    public AppUser(int appUserId, String email, String passwordHash, String displayName) {
        this.appUserId = appUserId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public int getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(int appUserId) {
        this.appUserId = appUserId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public int getLockoutCount() {
        return lockoutCount;
    }

    public void setLockoutCount(int lockoutCount) {
        this.lockoutCount = lockoutCount;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public void setSettingsJson(String settingsJson) {
        this.settingsJson = settingsJson;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDateTime getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(LocalDateTime updatedDate) {
        this.updatedDate = updatedDate;
    }
}
