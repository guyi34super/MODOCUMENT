package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ConnectorStateStore;
import com.modocument.domain.document.ConnectorState;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionConnectorStateEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

/** SQL-backed incremental crawl state, upserted by source id (last write wins). */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlConnectorStateStore implements ConnectorStateStore {

    private final ExtractionConnectorStateJpaRepository repository;

    public SqlConnectorStateStore(ExtractionConnectorStateJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ConnectorState> get(String sourceId) {
        return repository.findById(sourceId).map(SqlConnectorStateStore::toDomain);
    }

    @Override
    public ConnectorState save(ConnectorState state) {
        state.setUpdatedDate(Instant.now());
        ExtractionConnectorStateEntity entity = new ExtractionConnectorStateEntity();
        entity.setSourceId(state.getSourceId());
        entity.setEtagsJson(JsonColumns.toJson(state.getEtags()));
        entity.setLastModifiedJson(JsonColumns.toJson(state.getLastModified()));
        entity.setLastCursor(state.getLastCursor());
        entity.setLastWatermark(state.getLastWatermark());
        entity.setUpdatedDate(state.getUpdatedDate());
        repository.save(entity);
        return state;
    }

    private static ConnectorState toDomain(ExtractionConnectorStateEntity entity) {
        ConnectorState state = new ConnectorState();
        state.setSourceId(entity.getSourceId());
        state.setEtags(new LinkedHashMap<>(JsonColumns.fromJsonMap(entity.getEtagsJson())));
        state.setLastModified(new LinkedHashMap<>(JsonColumns.fromJsonMap(entity.getLastModifiedJson())));
        state.setLastCursor(entity.getLastCursor());
        state.setLastWatermark(entity.getLastWatermark());
        state.setUpdatedDate(entity.getUpdatedDate());
        return state;
    }
}
