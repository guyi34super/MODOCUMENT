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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** An application user account for JWT authentication. */
@Entity
// SCHEMA PARITY with V50 (ux_appusers_provider_subject) on the H2 test
// schema: best-effort mirror of the PostgreSQL partial unique index — plain
// unique constraints skip rows with NULLs on both engines, so local accounts
// (ProviderSubject NULL) stay unaffected.
@Table(name = "AppUsers", uniqueConstraints = @UniqueConstraint(
        name = "ux_appusers_provider_subject",
        columnNames = {"AuthProvider", "ProviderSubject"}))
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
     * External identity link (V50, ws07 OIDC): which front door this account
     * signs in through — NULL means local password login (break-glass), else
     * the OIDC registrationId ('entra', 'keycloak', ...).
     */
    @Column(name = "AuthProvider", length = 40)
    private String authProvider;

    /** The IdP's stable subject (sub claim); unique per AuthProvider. */
    @Column(name = "ProviderSubject", length = 255)
    private String providerSubject;

    @Column(name = "Email", length = 256, nullable = false, unique = true)
    private String email;

    @Column(name = "PasswordHash", length = 100, nullable = false)
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

    public String getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public void setProviderSubject(String providerSubject) {
        this.providerSubject = providerSubject;
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
