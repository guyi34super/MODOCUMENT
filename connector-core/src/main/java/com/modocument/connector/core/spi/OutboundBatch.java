package com.modocument.connector.core.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One outbound batch, already built by the server's payload builder.
 * Record-shaped targets (REST, DATABASE, WEBHOOK) use {@code records};
 * file-shaped targets (SFTP, released documents) use {@code fileName} +
 * {@code fileContent}. {@code kind} and {@code format} are the server's
 * {@code PayloadKind} / {@code PayloadFormat} names.
 */
public record OutboundBatch(String idempotencyKey, String kind, String format,
                            List<Map<String, Object>> records, String fileName,
                            String contentType, byte[] fileContent) {

    public OutboundBatch {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /**
     * Records per write call: the target spec's {@code batchSize}, else 100
     * for REST_API, 500 for DATABASE and everything at once otherwise.
     */
    public static int batchSize(ConnectionType type, Map<String, Object> targetSpec) {
        Object raw = targetSpec == null ? null : targetSpec.get("batchSize");
        int fallback = switch (type) {
            case REST_API -> 100;
            case DATABASE -> 500;
            default -> Integer.MAX_VALUE;
        };
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * This batch split into write calls of at most {@code size} records; a
     * file batch, or one that fits, stays whole. Chunk {@code n} of a split
     * batch carries the idempotency key {@code <key>-<n>}, so a retry repeats
     * exactly the same keys.
     */
    public List<OutboundBatch> chunks(int size) {
        if (fileContent != null || records.size() <= size) {
            return List.of(this);
        }
        List<OutboundBatch> chunks = new ArrayList<>();
        for (int from = 0, n = 0; from < records.size(); from += size, n++) {
            chunks.add(new OutboundBatch(idempotencyKey == null ? null : idempotencyKey + "-" + n,
                    kind, format, records.subList(from, Math.min(records.size(), from + size)),
                    fileName, contentType, null));
        }
        return chunks;
    }
}
