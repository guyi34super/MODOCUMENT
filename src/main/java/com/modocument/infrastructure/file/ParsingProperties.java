package com.modocument.infrastructure.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM table-extraction batching settings (all document formats). */
@ConfigurationProperties(prefix = "modocument.parsing")
public class ParsingProperties {

    private int llmBatchSize = 50;
    private int llmSinglePassMaxRows = 150;
    private int llmBatchConcurrency = 8;
    private boolean deterministicEnabled = true;
    private boolean combinedExtractionMapping = true;

    public int getLlmBatchSize() {
        return llmBatchSize;
    }

    public void setLlmBatchSize(int llmBatchSize) {
        this.llmBatchSize = llmBatchSize;
    }

    public int getLlmSinglePassMaxRows() {
        return llmSinglePassMaxRows;
    }

    public void setLlmSinglePassMaxRows(int llmSinglePassMaxRows) {
        this.llmSinglePassMaxRows = llmSinglePassMaxRows;
    }

    public int getLlmBatchConcurrency() {
        return llmBatchConcurrency;
    }

    public void setLlmBatchConcurrency(int llmBatchConcurrency) {
        this.llmBatchConcurrency = llmBatchConcurrency;
    }

    public boolean isDeterministicEnabled() {
        return deterministicEnabled;
    }

    public void setDeterministicEnabled(boolean deterministicEnabled) {
        this.deterministicEnabled = deterministicEnabled;
    }

    public boolean isCombinedExtractionMapping() {
        return combinedExtractionMapping;
    }

    public void setCombinedExtractionMapping(boolean combinedExtractionMapping) {
        this.combinedExtractionMapping = combinedExtractionMapping;
    }
}
