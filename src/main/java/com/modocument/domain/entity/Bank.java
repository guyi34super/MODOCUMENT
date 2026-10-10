package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A tenant bank. Every AppUser and every tenant-owned row carries a BankId;
 * pre-multi-tenancy data lives in the deterministic default bank seeded by
 * V10__bank_and_user_security_columns.sql.
 */
@Entity
@Table(name = "Bank")
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

    public Bank() {
    }

    public Bank(String code, String name) {
        this.code = code;
        this.name = name;
        this.createdDate = LocalDateTime.now();
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
}
