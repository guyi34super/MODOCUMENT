package com.modocument.domain.document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API connector settings — the compliant way to pull data from platforms
 * (incl. social networks) is their official API with the user's own
 * credentials. Secrets are never persisted: auth references an environment
 * variable resolved at fetch time, or a per-run token supplied when the job
 * starts (held in memory only).
 */
public class ApiSourceConfig {

    public enum AuthType { NONE, BEARER, BASIC, API_KEY_HEADER }

    public enum PaginationMode { NONE, PAGE, OFFSET, CURSOR, LINK_HEADER }

    private String baseUrl;
    private String path = "";
    private Map<String, String> headers = new LinkedHashMap<>();
    private AuthType authType = AuthType.NONE;
    /** Name of the env var holding the secret (never the secret itself). */
    private String authEnvVar;
    /** Header carrying the key when authType == API_KEY_HEADER. */
    private String authHeaderName;

    private PaginationMode paginationMode = PaginationMode.NONE;
    private String pageParam = "page";
    private String sizeParam;
    private int pageSize = 100;
    private Integer maxPages;
    private String cursorParam = "cursor";
    /** Dot-path into the response JSON where the next cursor lives. */
    private String cursorJsonPath;

    /** Dot-path into the response JSON where the record array lives ("" = root). */
    private String recordsJsonPath = "";
    /** Query parameter used for incremental pulls (filled from saved cursor). */
    private String incrementalParam;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getAuthEnvVar() {
        return authEnvVar;
    }

    public void setAuthEnvVar(String authEnvVar) {
        this.authEnvVar = authEnvVar;
    }

    public String getAuthHeaderName() {
        return authHeaderName;
    }

    public void setAuthHeaderName(String authHeaderName) {
        this.authHeaderName = authHeaderName;
    }

    public PaginationMode getPaginationMode() {
        return paginationMode;
    }

    public void setPaginationMode(PaginationMode paginationMode) {
        this.paginationMode = paginationMode;
    }

    public String getPageParam() {
        return pageParam;
    }

    public void setPageParam(String pageParam) {
        this.pageParam = pageParam;
    }

    public String getSizeParam() {
        return sizeParam;
    }

    public void setSizeParam(String sizeParam) {
        this.sizeParam = sizeParam;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public String getCursorParam() {
        return cursorParam;
    }

    public void setCursorParam(String cursorParam) {
        this.cursorParam = cursorParam;
    }

    public String getCursorJsonPath() {
        return cursorJsonPath;
    }

    public void setCursorJsonPath(String cursorJsonPath) {
        this.cursorJsonPath = cursorJsonPath;
    }

    public String getRecordsJsonPath() {
        return recordsJsonPath;
    }

    public void setRecordsJsonPath(String recordsJsonPath) {
        this.recordsJsonPath = recordsJsonPath;
    }

    public String getIncrementalParam() {
        return incrementalParam;
    }

    public void setIncrementalParam(String incrementalParam) {
        this.incrementalParam = incrementalParam;
    }
}
