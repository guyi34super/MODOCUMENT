package com.modocument.agent.exec;

import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.wire.AgentSftpFile;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * SFTP connections: connector-core's
 * {@link com.modocument.connector.core.sftp.SftpConnector} — pinned host key
 * (no trust on first use), password or private-key auth from the local
 * secret, every path confined to the connection's folder, files over the cap
 * skipped (and logged), uploads via a temporary name then an atomic rename.
 * A READ posts one {@link AgentSftpFile} record per file; the files move to
 * {@code processed/} (or are deleted) only once MoDocument accepted them.
 */
public class SftpConnector extends CoreConnector {

    public SftpConnector() {
        super(new com.modocument.connector.core.sftp.SftpConnector(LIMITS, Clock.systemUTC()));
    }

    @Override
    protected List<Map<String, Object>> records(ReadBatch batch) {
        return batch.files().stream()
                .map(file -> AgentSftpFile.of(file.fileName(), file.remotePath(), file.content()).toRecord())
                .toList();
    }
}
