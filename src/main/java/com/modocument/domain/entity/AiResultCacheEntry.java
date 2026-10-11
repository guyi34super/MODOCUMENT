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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One cached AI parse result (ws28, V286): the parsed table the model
 * produced for one document text + template version + mode + model + prompt
 * version, reused when the same content is parsed again in the same bank.
 * ResultJson holds customer data — RLS-scoped by BankId and deleted by the
 * retention purge. Mirrors V286__ws28_ai_result_cache.sql on the H2 schema.
 */
@Entity
@Table(name = "AiResultCache",
        uniqueConstraints = @UniqueConstraint(name = "ux_airesultcache_bank_key",
                columnNames = {"BankId", "CacheKey"}),
        indexes = @Index(name = "ix_airesultcache_bankid_createdat", columnList = "BankId, CreatedAt"))
public class AiResultCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AiResultCacheId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "CacheKey", length = 64, nullable = false)
    private String cacheKey;

    @Column(name = "PromptVersion", length = 20, nullable = false)
    private String promptVersion;

    @Column(name = "Model", length = 200, nullable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ResultJson", nullable = false)
    private String resultJson;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @ColumnDefault("0")
    @Column(name = "HitCount", nullable = false)
    private int hitCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getHitCount() {
        return hitCount;
    }

    public void setHitCount(int hitCount) {
        this.hitCount = hitCount;
    }
}
