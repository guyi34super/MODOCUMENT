package com.modocument.application.dto;

import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.DbSourceConfig;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.SourceType;

/** Create/update body for an extraction source. Carries no secrets. */
public class SaveExtractionSourceRequest {

    private String name;
    private SourceType type;
    private boolean termsAcknowledged;
    private String dataSpec;
    private WebSourceConfig web;
    private ApiSourceConfig api;
    private DbSourceConfig db;

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
}
