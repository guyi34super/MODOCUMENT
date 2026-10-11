package com.modocument.connector.core.spi;

import java.util.List;
import java.util.Map;

/**
 * One inbound page: {@code records} for REST/DATABASE sources, {@code files}
 * for SFTP. {@code nextCursor} is the watermark / page token to persist when
 * the run succeeds; {@code notes} are admin-safe remarks about what the read
 * skipped (a file over the size cap); {@code acknowledgement} is the
 * deferred second phase (see {@link ReadAcknowledgement}).
 */
public record ReadBatch(List<Map<String, Object>> records, List<InboundFile> files,
                        String nextCursor, boolean hasMore, List<String> notes,
                        ReadAcknowledgement acknowledgement) {

    public ReadBatch {
        records = records == null ? List.of() : List.copyOf(records);
        files = files == null ? List.of() : List.copyOf(files);
        notes = notes == null ? List.of() : List.copyOf(notes);
        acknowledgement = acknowledgement == null ? ReadAcknowledgement.NONE : acknowledgement;
    }

    public ReadBatch(List<Map<String, Object>> records, List<InboundFile> files, String nextCursor,
                     boolean hasMore) {
        this(records, files, nextCursor, hasMore, List.of(), ReadAcknowledgement.NONE);
    }

    /** Acknowledges every file of this batch (see {@link ReadAcknowledgement}). */
    public void acknowledgeAll() throws Exception {
        if (!files.isEmpty()) {
            acknowledgement.acknowledge(files.stream().map(InboundFile::remotePath).toList());
        }
    }

    /** A pulled file; {@code remotePath} is relative to the connection's base folder. */
    public record InboundFile(String remotePath, String fileName, byte[] content) {
    }
}
