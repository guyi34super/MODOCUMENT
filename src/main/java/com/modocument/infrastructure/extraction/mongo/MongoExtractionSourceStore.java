package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.domain.document.ExtractionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoExtractionSourceStore implements ExtractionSourceStore {

    private final MongoTemplate mongo;

    public MongoExtractionSourceStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public ExtractionSource save(ExtractionSource source) {
        if (source.getId() == null) {
            source.setCreatedDate(Instant.now());
        }
        source.setUpdatedDate(Instant.now());
        return mongo.save(source);
    }

    @Override
    public Optional<ExtractionSource> getById(String id) {
        return Optional.ofNullable(mongo.findById(id, ExtractionSource.class));
    }

    @Override
    public Optional<ExtractionSource> getByIdAndOwner(String id, int ownerUserId) {
        return Optional.ofNullable(mongo.findOne(
                new Query(Criteria.where("_id").is(id).and("ownerUserId").is(ownerUserId)),
                ExtractionSource.class));
    }

    @Override
    public List<ExtractionSource> list() {
        return mongo.find(new Query().with(Sort.by("createdDate")), ExtractionSource.class);
    }

    @Override
    public List<ExtractionSource> listByOwner(int ownerUserId) {
        return mongo.find(
                new Query(Criteria.where("ownerUserId").is(ownerUserId)).with(Sort.by("createdDate")),
                ExtractionSource.class);
    }

    @Override
    public void delete(String id) {
        getById(id).ifPresent(mongo::remove);
    }
}
