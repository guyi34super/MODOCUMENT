package com.modocument.application.dto;

import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.DbSourceConfig;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.SourceType;

import java.time.Instant;

/** Extraction source as returned by the API (configs never hold secrets). */
public record ExtractionSourceDto(String id, String name, SourceType type,
                                  boolean termsAcknowledged, String dataSpec,
                                  WebSourceConfig web, ApiSourceConfig api, DbSourceConfig db,
                                  Instant createdDate, Instant updatedDate) {

    public static ExtractionSourceDto from(ExtractionSource source) {
        return new ExtractionSourceDto(source.getId(), source.getName(), source.getType(),
                source.isTermsAcknowledged(), source.getDataSpec(), source.getWeb(),
                source.getApi(), source.getDb(), source.getCreatedDate(), source.getUpdatedDate());
    }
}
