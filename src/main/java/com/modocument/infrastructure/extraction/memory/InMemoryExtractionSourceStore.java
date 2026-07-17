package com.modocument.infrastructure.extraction.memory;

import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.domain.document.ExtractionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryExtractionSourceStore implements ExtractionSourceStore {

    private final Map<String, ExtractionSource> sources = new ConcurrentHashMap<>();

    @Override
    public ExtractionSource save(ExtractionSource source) {
        if (source.getId() == null) {
            source.setId(UUID.randomUUID().toString());
            source.setCreatedDate(Instant.now());
        }
        source.setUpdatedDate(Instant.now());
        sources.put(source.getId(), source);
        return source;
    }

    @Override
    public Optional<ExtractionSource> getById(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    @Override
    public List<ExtractionSource> list() {
        return sources.values().stream()
                .sorted(Comparator.comparing(ExtractionSource::getCreatedDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public void delete(String id) {
        sources.remove(id);
    }
}
