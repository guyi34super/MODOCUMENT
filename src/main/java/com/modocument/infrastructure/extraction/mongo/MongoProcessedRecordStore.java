package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.domain.document.ProcessedRecord;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoProcessedRecordStore implements ProcessedRecordStore {

    private final MongoTemplate mongo;

    public MongoProcessedRecordStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public int saveAllIfNew(List<ProcessedRecord> batch) {
        int saved = 0;
        for (ProcessedRecord record : batch) {
            if (record.getProcessedAt() == null) {
                record.setProcessedAt(Instant.now());
            }
            try {
                mongo.insert(record);
                saved++;
            } catch (DuplicateKeyException e) {
                // Same (jobId, dedupKey) already stored — idempotent replay.
            }
        }
        return saved;
    }

    @Override
    public List<ProcessedRecord> findBatch(String jobId, String afterId, int limit) {
        Criteria criteria = Criteria.where("jobId").is(jobId);
        if (afterId != null) {
            criteria = new Criteria().andOperator(criteria,
                    Criteria.where("_id").gt(new ObjectId(afterId)));
        }
        return mongo.find(new Query(criteria).with(Sort.by("_id")).limit(limit),
                ProcessedRecord.class);
    }

    @Override
    public long countByJob(String jobId) {
        return mongo.count(new Query(Criteria.where("jobId").is(jobId)), ProcessedRecord.class);
    }
}
