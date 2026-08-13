package com.modocument.infrastructure.extraction.sql.entity;

import com.modocument.domain.model.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * SQL row backing an extraction source (extraction.store=sql). Must stay in
 * sync with V8__extraction_sql_store.sql — the test profile builds its H2
 * schema from this class, production from the migration.
 */
@Entity
@Table(name = "ExtractionSource",
        indexes = @Index(name = "ix_extractionsource_owneruserid", columnList = "OwnerUserId"))
public class ExtractionSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ExtractionSourceId")
    private Long id;

    @Column(name = "OwnerUserId", nullable = false)
    private int ownerUserId;

    @Column(name = "Name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", length = 20, nullable = false)
    private SourceType type;

    @Column(name = "TermsAcknowledged", nullable = false)
    private boolean termsAcknowledged;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "DataSpec")
    private String dataSpec;

    @Column(name = "TemplateId")
    private Integer templateId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "WebConfigJson")
    private String webConfigJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ApiConfigJson")
    private String apiConfigJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "DbConfigJson")
    private String dbConfigJson;

    @Column(name = "CreatedDate")
    private Instant createdDate;

    @Column(name = "UpdatedDate")
    private Instant updatedDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public boolean isTermsAcknowledged() {
        return termsAcknowledged;
    }

    public void setTermsAcknowledged(boolean termsAcknowledged) {
        this.termsAcknowledged = termsAcknowledged;
    }

    public String getDataSpec() {
        return dataSpec;
    }

    public void setDataSpec(String dataSpec) {
        this.dataSpec = dataSpec;
    }

    public Integer getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Integer templateId) {
        this.templateId = templateId;
    }

    public String getWebConfigJson() {
        return webConfigJson;
    }

    public void setWebConfigJson(String webConfigJson) {
        this.webConfigJson = webConfigJson;
    }

    public String getApiConfigJson() {
        return apiConfigJson;
    }

    public void setApiConfigJson(String apiConfigJson) {
        this.apiConfigJson = apiConfigJson;
    }

    public String getDbConfigJson() {
        return dbConfigJson;
    }

    public void setDbConfigJson(String dbConfigJson) {
        this.dbConfigJson = dbConfigJson;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Instant updatedDate) {
        this.updatedDate = updatedDate;
    }
}
