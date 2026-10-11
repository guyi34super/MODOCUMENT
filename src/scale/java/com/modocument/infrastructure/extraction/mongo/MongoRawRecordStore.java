package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.RawRecord;
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
public class MongoRawRecordStore implements RawRecordStore {

    private final MongoTemplate mongo;

    public MongoRawRecordStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean saveIfNew(RawRecord record) {
        if (record.getFetchedAt() == null) {
            record.setFetchedAt(Instant.now());
        }
        try {
            mongo.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<RawRecord> findBatch(String jobId, String afterId, int limit) {
        Criteria criteria = Criteria.where("jobId").is(jobId);
        if (afterId != null) {
            criteria = new Criteria().andOperator(criteria,
                    Criteria.where("_id").gt(new ObjectId(afterId)));
        }
        return mongo.find(new Query(criteria).with(Sort.by("_id")).limit(limit), RawRecord.class);
    }

    @Override
    public long countByJob(String jobId) {
        return mongo.count(new Query(Criteria.where("jobId").is(jobId)), RawRecord.class);
    }
}
