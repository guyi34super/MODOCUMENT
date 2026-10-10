package com.modocument.application.dto;

import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;

import java.util.List;

/**
 * Response of {@code POST /api/extraction/jobs/{id}/infer-schema}: the
 * suggested fields plus their provenance, the same way an ingestion job
 * reports {@code parsedBy}/{@code parseFallbackReason}.
 *
 * @param generatedBy    {@code AI} when the model inferred the schema,
 *                       {@code CODE} when it was inferred from the sample
 *                       values because the AI was unavailable or failed
 * @param fallbackReason why code was used ({@link FallbackReason}); null for AI
 */
public record InferredSchemaResult(List<InferredSchema.InferredField> fields,
                                   ParsedBy generatedBy,
                                   FallbackReason fallbackReason) {

    public static InferredSchemaResult ai(List<InferredSchema.InferredField> fields) {
        return new InferredSchemaResult(fields, ParsedBy.AI, null);
    }

    public static InferredSchemaResult code(List<InferredSchema.InferredField> fields,
                                            FallbackReason reason) {
        return new InferredSchemaResult(fields, ParsedBy.CODE, reason);
    }
}
