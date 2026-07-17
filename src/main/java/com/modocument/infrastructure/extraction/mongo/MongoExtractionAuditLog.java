package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.domain.document.ExtractionAuditEvent;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoExtractionAuditLog implements ExtractionAuditLog {

    private final MongoTemplate mongo;

    public MongoExtractionAuditLog(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void add(ExtractionAuditEvent event) {
        mongo.insert(event);
    }

    @Override
    public List<ExtractionAuditEvent> findByJob(String jobId, String afterId, int limit) {
        Criteria criteria = Criteria.where("jobId").is(jobId);
        if (afterId != null) {
            criteria = new Criteria().andOperator(criteria,
                    Criteria.where("_id").gt(new ObjectId(afterId)));
        }
        return mongo.find(new Query(criteria).with(Sort.by("_id")).limit(limit),
                ExtractionAuditEvent.class);
    }
}
