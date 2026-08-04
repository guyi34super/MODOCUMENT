package com.modocument.application.port;

import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseResult;

/**
 * Parses an uploaded document into tabular form and optional mapping suggestions:
 * deterministic parsing for structured files, document extraction + LLM
 * tabularization for everything else.
 */
public interface IngestionParseStep {

    ParseResult parse(ParseContext context);
}
