package com.modocument.infrastructure.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for how Excel workbooks are turned into a table. By default they are
 * serialized (formulas evaluated, merged cells filled, full width) and handed to
 * the LLM like every other document type, so the same {@code TableData} JSON
 * comes back regardless of source format. Turn {@code llmEnabled} off to fall
 * back to the deterministic POI parser — useful for keyless/offline operation,
 * where the LLM route would otherwise return 503.
 */
@ConfigurationProperties(prefix = "modocument.parsing.excel")
public class ExcelParsingProperties {

    private boolean llmEnabled = true;

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }
}
