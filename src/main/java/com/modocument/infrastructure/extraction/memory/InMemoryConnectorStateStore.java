package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ConnectorStateStore;
import com.modocument.domain.document.ConnectorState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryConnectorStateStore implements ConnectorStateStore {

    private final Map<String, ConnectorState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<ConnectorState> get(String sourceId) {
        return Optional.ofNullable(states.get(sourceId));
    }

    @Override
    public ConnectorState save(ConnectorState state) {
        state.setUpdatedDate(Instant.now());
        states.put(state.getSourceId(), state);
        return state;
    }
}
