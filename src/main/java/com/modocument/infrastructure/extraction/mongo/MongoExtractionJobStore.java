package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ExtractionJobStore;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.ExtractionJobStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoExtractionJobStore implements ExtractionJobStore {

    private final MongoTemplate mongo;

    public MongoExtractionJobStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public ExtractionJob save(ExtractionJob job) {
        if (job.getId() == null) {
            job.setCreatedDate(Instant.now());
        }
        job.setUpdatedDate(Instant.now());
        return mongo.save(job);
    }

    @Override
    public Optional<ExtractionJob> getById(String id) {
        return Optional.ofNullable(mongo.findById(id, ExtractionJob.class));
    }

    @Override
    public Optional<ExtractionJob> getByIdAndOwner(String id, int ownerUserId) {
        return Optional.ofNullable(mongo.findOne(
                new Query(Criteria.where("_id").is(id).and("ownerUserId").is(ownerUserId)),
                ExtractionJob.class));
    }

    @Override
    public List<ExtractionJob> list() {
        return mongo.find(new Query().with(Sort.by("createdDate")), ExtractionJob.class);
    }

    @Override
    public List<ExtractionJob> listByOwner(int ownerUserId) {
        return mongo.find(
                new Query(Criteria.where("ownerUserId").is(ownerUserId)).with(Sort.by("createdDate")),
                ExtractionJob.class);
    }

    @Override
    public void increment(String jobId, String counter, long delta) {
        mongo.updateFirst(
                new Query(Criteria.where("_id").is(jobId)),
                new Update().inc("counters." + counter, delta).set("updatedDate", Instant.now()),
                ExtractionJob.class);
    }

    @Override
    public Map<ExtractionJobStatus, Long> countByStatusSince(int bankId, Instant since) {
        // Status-only projection: the dashboard needs counts, never job bodies.
        Query query = new Query(Criteria.where("bankId").is(bankId)
                .and("createdDate").gte(since));
        query.fields().include("status");
        Map<ExtractionJobStatus, Long> counts = new EnumMap<>(ExtractionJobStatus.class);
        for (ExtractionJob job : mongo.find(query, ExtractionJob.class)) {
            if (job.getStatus() != null) {
                counts.merge(job.getStatus(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
