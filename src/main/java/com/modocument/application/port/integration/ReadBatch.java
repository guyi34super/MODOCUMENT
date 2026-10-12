package com.modocument.application.port.integration;

import com.modocument.connector.core.spi.ReadAcknowledgement;

import java.util.List;
import java.util.Map;

/**
 * One inbound page (ws25): {@code records} for REST/DATABASE sources,
 * {@code files} for SFTP (each goes through the normal file ingestion, so AI
 * or code extraction applies). {@code nextCursor} is the watermark/page token
 * to persist when the run succeeds. {@code notes} are admin-safe remarks
 * about what the read skipped (an SFTP file over the size cap).
 *
 * <p>Two-phase reads (connector-sharing.md): an SFTP read moves or deletes
 * nothing; the caller runs {@link #acknowledge} with the files it has safely
 * stored (their ingestion jobs exist), and only those are moved to
 * {@code processed/} or deleted.
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

    /** The read's second phase for {@code stored} (files of this batch); a no-op for none. */
    public void acknowledge(List<InboundFile> stored) throws Exception {
        if (!stored.isEmpty()) {
            acknowledgement.acknowledge(stored.stream().map(InboundFile::remotePath).toList());
        }
    }

    /** {@link #acknowledge} for every file of this batch. */
    public void acknowledgeAll() throws Exception {
        acknowledge(files);
    }

    /** A pulled file; {@code remotePath} is relative to the configured folder. */
    public record InboundFile(String remotePath, String fileName, byte[] content) {
    }
}
