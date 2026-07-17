package com.modocument.application.port;

import com.modocument.application.dto.TableData;

/**
 * Parses an uploaded document (by stored file name) into tabular form:
 * deterministic parsing for structured files, document extraction + LLM
 * tabularization for everything else.
 */
public interface IngestionParseStep {

    TableData parse(String fileName);
}
