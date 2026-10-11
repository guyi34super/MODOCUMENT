package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One administrator-changed setting of one bank (V200, ws20). A customer
 * bank's row is its override; the platform bank's row is the platform layer
 * (the inherited default in {@code ValueText} plus the bank bounds
 * {@code BankMinText}/{@code BankMaxText}). Values are canonical text parsed
 * against the code catalogue ({@code SettingDefinitions}); {@code ValueText}
 * NULL means "inherit". Rows are never deleted, so {@link #getVersion()} only
 * grows — it is the concurrency token the settings API hands out.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it must be kept in sync with
 * V200__ws20_settings.sql by hand. The Bank foreign key is PostgreSQL-only
 * (a plain int here, like PendingChange).
 */
@Entity
@Table(name = "BankSetting", uniqueConstraints = @UniqueConstraint(
        name = "uq_banksetting_bank_key", columnNames = {"BankId", "SettingKey"}))
public class BankSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BankSettingId")
    private int bankSettingId;

    /** The owning bank; RLS substrate. The platform bank's rows are the platform layer. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "SettingKey", length = 100, nullable = false)
    private String settingKey;

    /** Canonical value text; NULL = inherit from the next layer. */
    @Column(name = "ValueText", length = 2000)
    private String valueText;

    /** Platform rows only: the lowest value a bank may choose. */
    @Column(name = "BankMinText", length = 200)
    private String bankMinText;

    /** Platform rows only: the highest value a bank may choose. */
    @Column(name = "BankMaxText", length = 200)
    private String bankMaxText;

    /**
     * Bumped by every write, never reset; a new row starts at 1, so 0 always
     * means "no row yet". Checked against the caller's expected version under
     * a row lock ({@code SettingsStore}), not via JPA {@code @Version}.
     */
    @Column(name = "Version", nullable = false)
    private int version;

    @Column(name = "UpdatedByUserId")
    private Integer updatedByUserId;

    @Column(name = "UpdatedAt", nullable = false)
    private Instant updatedAt;

    public BankSetting() {
    }

    public BankSetting(int bankId, String settingKey) {
        this.bankId = bankId;
        this.settingKey = settingKey;
    }

    public int getBankSettingId() {
        return bankSettingId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getValueText() {
        return valueText;
    }

    public void setValueText(String valueText) {
        this.valueText = valueText;
    }

    public String getBankMinText() {
        return bankMinText;
    }

    public void setBankMinText(String bankMinText) {
        this.bankMinText = bankMinText;
    }

    public String getBankMaxText() {
        return bankMaxText;
    }

    public void setBankMaxText(String bankMaxText) {
        this.bankMaxText = bankMaxText;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Integer getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(Integer updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
