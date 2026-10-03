package com.modocument.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.modocument.domain.model.SourceType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A configured, authorized place to extract data from. Creation requires
 * termsAcknowledged=true — the caller confirms they are permitted to access
 * this source under its terms of service and applicable data-protection law.
 */
@Document(collection = "extractionSources")
public class ExtractionSource {

    @Id
    private String id;
    /** The AppUser who owns this source — every read/write is scoped to it. */
    private int ownerUserId;
    /** The owner's bank, stamped on create; RLS substrate. */
    private int bankId;
    private String name;
    private SourceType type;
    /** Caller's confirmation that access to this source is authorized. */
    private boolean termsAcknowledged;
    /** Free-text description of the data wanted; guides LLM cleaning/schema. */
    private String dataSpec;
    /**
     * Optional template whose fields define the data spec when dataSpec text
     * is blank. Jobs created from this source inherit it (for bridging too).
     */
    private Integer templateId;

    private WebSourceConfig web;
    private ApiSourceConfig api;
    private DbSourceConfig db;

    private Instant createdDate;
    private Instant updatedDate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @JsonIgnore
    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @JsonIgnore
    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
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

    public WebSourceConfig getWeb() {
        return web;
    }

    public void setWeb(WebSourceConfig web) {
        this.web = web;
    }

    public ApiSourceConfig getApi() {
        return api;
    }

    public void setApi(ApiSourceConfig api) {
        this.api = api;
    }

    public DbSourceConfig getDb() {
        return db;
    }

    public void setDb(DbSourceConfig db) {
        this.db = db;
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
