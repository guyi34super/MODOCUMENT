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
     * Ask the LLM about columns for mapping. Scope and sample size are
     * configured via {@link #llmScope} and {@link #llmSampleSize}.
     */
    private boolean llmFallbackEnabled = true;

    /**
     * {@code all} sends every source column and template field to the LLM for
     * holistic assignment; {@code unresolved} limits the LLM to columns code
     * tiers could not map (cheaper for cloud providers).
     */
    private String llmScope = "unresolved";

    /** Sample values per column included in the LLM mapping prompt. */
    private int llmSampleSize = 5;

    /** Minimum candidate score for a code-tier match to be accepted. */
    private double confidenceThreshold = 0.7;

    /** Non-blank sample values per column examined by the value-shape tier. */
    private int sampleSize = 20;

    /** Recent completed jobs consulted by the learned-mappings tier. */
    private int historyJobs = 25;

    /**
     * Ceiling on column × field pairs the value-shape tier may evaluate. Shape
     * scoring runs every sample through every rule of a field, so a wide file
     * against a wide template grows multiplicatively inside the upload
     * request; beyond this budget shape is simply no signal and the name,
     * history and LLM tiers carry the mapping.
     */
    private int shapePairBudget = 400;

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

    public String getLlmScope() {
        return llmScope;
    }

    public void setLlmScope(String llmScope) {
        this.llmScope = llmScope;
    }

    public boolean isLlmScopeAll() {
        return llmScope == null || llmScope.isBlank() || "all".equalsIgnoreCase(llmScope);
    }

    public int getLlmSampleSize() {
        return llmSampleSize;
    }

    public void setLlmSampleSize(int llmSampleSize) {
        this.llmSampleSize = llmSampleSize;
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

    public int getShapePairBudget() {
        return shapePairBudget;
    }

    public void setShapePairBudget(int shapePairBudget) {
        this.shapePairBudget = shapePairBudget;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases;
    }
}
