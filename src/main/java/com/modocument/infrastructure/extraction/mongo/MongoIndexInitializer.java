package com.modocument.infrastructure.extraction.mongo;

import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the indexes the extraction pipeline's correctness relies on:
 * unique dedup keys (idempotent enqueue/replay) and the claim/scan paths.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoIndexInitializer {

    private final MongoTemplate mongo;

    public MongoIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(ExtractionTask.class).ensureIndex(new Index()
                .on("jobId", Sort.Direction.ASC)
                .on("dedupKey", Sort.Direction.ASC)
                .unique().sparse().named("job_dedup"));
        mongo.indexOps(ExtractionTask.class).ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("nextAttemptAt", Sort.Direction.ASC)
                .named("claim_scan"));
        mongo.indexOps(RawRecord.class).ensureIndex(new Index()
                .on("sourceId", Sort.Direction.ASC)
                .on("contentHash", Sort.Direction.ASC)
                .unique().sparse().named("source_hash"));
        mongo.indexOps(ProcessedRecord.class).ensureIndex(new Index()
                .on("jobId", Sort.Direction.ASC)
                .on("dedupKey", Sort.Direction.ASC)
                .unique().sparse().named("job_dedup"));
    }
}
