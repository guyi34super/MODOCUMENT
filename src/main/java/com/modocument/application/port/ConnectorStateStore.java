package com.modocument.application.port;

import com.modocument.domain.document.ConnectorState;

import java.util.Optional;

/** Persistence port for per-source incremental state (ETags/cursors/watermarks). */
public interface ConnectorStateStore {

    Optional<ConnectorState> get(String sourceId);

    ConnectorState save(ConnectorState state);
}
