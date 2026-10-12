package com.modocument.connector.core.spi;

import java.util.Collection;

/**
 * The second phase of a read (connector-sharing.md §4, "two-phase reads"):
 * the caller runs it AFTER the pulled data is safely stored — the server
 * once the file's ingestion job exists, the agent once {@code POST
 * /runs/{id}/records} answered. SFTP moves (or deletes) exactly the
 * acknowledged files; a failed upload or ingestion leaves the others in place
 * for the next run. DATABASE and REST reads have nothing to acknowledge.
 */
@FunctionalInterface
public interface ReadAcknowledgement {

    ReadAcknowledgement NONE = remotePaths -> { };

    /**
     * Runs the deferred after-read action for these files.
     *
     * @param remotePaths {@link ReadBatch.InboundFile#remotePath()} values of
     *                    the batch; other values are ignored
     */
    void acknowledge(Collection<String> remotePaths) throws Exception;
}
