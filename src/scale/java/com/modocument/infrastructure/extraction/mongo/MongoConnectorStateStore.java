package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ConnectorStateStore;
import com.modocument.domain.document.ConnectorState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoConnectorStateStore implements ConnectorStateStore {

    private final MongoTemplate mongo;

    public MongoConnectorStateStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<ConnectorState> get(String sourceId) {
        return Optional.ofNullable(mongo.findById(sourceId, ConnectorState.class));
    }

    @Override
    public ConnectorState save(ConnectorState state) {
        state.setUpdatedDate(Instant.now());
        return mongo.save(state);
    }
}
