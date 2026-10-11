package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;

import java.util.List;
import java.util.Map;

/**
 * One outbound batch (ws25), already built by the payload builder: masked
 * per the flow, capped by ExportGuard, mapped to the bank's column/key names.
 * Record-shaped targets (REST, DATABASE, WEBHOOK JSON) use {@code records};
 * file-shaped targets (SFTP, released documents) use {@code fileName} +
 * {@code fileContent}.
 */
public record OutboundBatch(String idempotencyKey, PayloadKind kind, PayloadFormat format,
                            List<Map<String, Object>> records, String fileName,
                            String contentType, byte[] fileContent) {

    public OutboundBatch {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
