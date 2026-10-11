package com.modocument.application.port.integration;

import java.util.List;
import java.util.Map;

/**
 * One inbound page (ws25): {@code records} for REST/DATABASE sources,
 * {@code files} for SFTP (each goes through the normal file ingestion, so AI
 * or code extraction applies). {@code nextCursor} is the watermark/page token
 * to persist when the run succeeds.
 */
public record ReadBatch(List<Map<String, Object>> records, List<InboundFile> files,
                        String nextCursor, boolean hasMore) {

    public ReadBatch {
        records = records == null ? List.of() : List.copyOf(records);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** A pulled file; {@code remotePath} is relative to the configured folder. */
    public record InboundFile(String remotePath, String fileName, byte[] content) {
    }
}
