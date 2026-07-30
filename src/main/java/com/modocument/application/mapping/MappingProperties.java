package com.modocument.application.mapping;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tuning for the tiered column mapper. The deterministic code tiers (exact,
 * normalized, aliases/similarity, value-shape, learned history) always run;
 * the LLM is consulted only for columns they leave unresolved — and can be
 * switched off entirely for a fully code-only mapping flow.
 */
@ConfigurationProperties(prefix = "mapping")
public class MappingProperties {

    /**
     * Ask the LLM about columns the code tiers could not resolve. The prompt
     * carries column and field NAMES only — never data values.
     */
    private boolean llmFallbackEnabled = true;

    /** Minimum candidate score for a code-tier match to be accepted. */
    private double confidenceThreshold = 0.7;

    /** Non-blank sample values per column examined by the value-shape tier. */
    private int sampleSize = 20;

    /** Recent completed jobs consulted by the learned-mappings tier. */
    private int historyJobs = 25;

    /**
     * Extra header aliases (token -> canonical token), merged with the
     * built-in table; matched on normalized tokens (e.g. "matricule: employeenumber").
     */
    private Map<String, String> aliases = new LinkedHashMap<>();

    public boolean isLlmFallbackEnabled() {
        return llmFallbackEnabled;
    }

    public void setLlmFallbackEnabled(boolean llmFallbackEnabled) {
        this.llmFallbackEnabled = llmFallbackEnabled;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    public int getHistoryJobs() {
        return historyJobs;
    }

    public void setHistoryJobs(int historyJobs) {
        this.historyJobs = historyJobs;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases;
    }
}
