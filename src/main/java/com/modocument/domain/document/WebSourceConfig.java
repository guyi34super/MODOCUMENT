package com.modocument.domain.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Website connector settings. Deliberately has no fields for cookies,
 * sessions, or credentials: the web connector only fetches what is publicly
 * reachable and allowed by robots.txt — never behind a login or paywall.
 */
public class WebSourceConfig {

    private List<String> seedUrls = new ArrayList<>();
    /** Hosts the crawl may touch; links to any other host are never followed. */
    private List<String> allowedDomains = new ArrayList<>();
    private Integer maxDepth;
    private Integer maxPages;
    /** Kept for API compatibility: JS rendering is unavailable (ws29), the page is fetched as plain HTTP. */
    private boolean renderJs;
    /** Extra politeness delay; only ever raises the effective per-host delay. */
    private Long perHostDelayMillis;

    public List<String> getSeedUrls() {
        return seedUrls;
    }

    public void setSeedUrls(List<String> seedUrls) {
        this.seedUrls = seedUrls;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains;
    }

    public Integer getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public boolean isRenderJs() {
        return renderJs;
    }

    public void setRenderJs(boolean renderJs) {
        this.renderJs = renderJs;
    }

    public Long getPerHostDelayMillis() {
        return perHostDelayMillis;
    }

    public void setPerHostDelayMillis(Long perHostDelayMillis) {
        this.perHostDelayMillis = perHostDelayMillis;
    }
}
