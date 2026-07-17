package com.modocument.infrastructure.extraction.mongo;

import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.ExtractionTaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production lease queue: task documents claimed with atomic findAndModify,
 * so concurrent workers (on any instance) never double-claim. The unique
 * (jobId, dedupKey) index enforces enqueue idempotency.
 */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class MongoExtractionTaskQueue implements ExtractionTaskQueue {

    private final MongoTemplate mongo;

    public MongoExtractionTaskQueue(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean enqueue(ExtractionTask task) {
        task.setStatus(ExtractionTaskStatus.PENDING);
        if (task.getCreatedDate() == null) {
            task.setCreatedDate(Instant.now());
        }
        task.setUpdatedDate(Instant.now());
        try {
            mongo.insert(task);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<ExtractionTask> claim(String owner, String node, int max, Duration lease) {
        Instant now = Instant.now();
        List<ExtractionTask> claimed = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Query query = new Query(readyCriteria(node, now)).with(Sort.by("_id"));
            Update update = new Update()
                    .set("status", ExtractionTaskStatus.LEASED)
                    .set("leaseOwner", owner)
                    .set("leaseExpiresAt", now.plus(lease))
                    .set("updatedDate", now)
                    .inc("attempts", 1);
            ExtractionTask task = mongo.findAndModify(query, update,
                    FindAndModifyOptions.options().returnNew(true), ExtractionTask.class);
            if (task == null) {
                break;
            }
            claimed.add(task);
        }
        return claimed;
    }

    private Criteria readyCriteria(String node, Instant now) {
        return new Criteria().andOperator(
                Criteria.where("status").is(ExtractionTaskStatus.PENDING),
                Criteria.where("nextAttemptAt").lte(now),
                new Criteria().orOperator(
                        Criteria.where("nodeAffinity").isNull(),
                        Criteria.where("nodeAffinity").is(node)));
    }

    @Override
    public void renew(String taskId, String owner, Duration extension) {
        mongo.updateFirst(
                new Query(leasedBy(taskId, owner)),
                new Update().set("leaseExpiresAt", Instant.now().plus(extension)),
                ExtractionTask.class);
    }

    @Override
    public void complete(String taskId, String owner, ExtractionTaskStatus terminal) {
        ExtractionTask task = mongo.findAndModify(
                new Query(leasedBy(taskId, owner)),
                new Update()
                        .set("status", terminal)
                        .unset("leaseOwner")
                        .unset("leaseExpiresAt")
                        .set("updatedDate", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), ExtractionTask.class);
        if (task == null) {
            throw new IllegalStateException("Task " + taskId + " is not leased by " + owner + ".");
        }
    }

    @Override
    public boolean fail(String taskId, String owner, String error, Duration baseBackoff) {
        ExtractionTask task = mongo.findOne(
                new Query(leasedBy(taskId, owner)), ExtractionTask.class);
        if (task == null) {
            throw new IllegalStateException("Task " + taskId + " is not leased by " + owner + ".");
        }
        Update update = new Update()
                .set("lastError", error)
                .unset("leaseOwner")
                .unset("leaseExpiresAt")
                .set("updatedDate", Instant.now());
        boolean retry = task.getAttempts() < task.getMaxAttempts();
        if (retry) {
            long backoffMillis =
                    baseBackoff.toMillis() * (1L << Math.min(task.getAttempts() - 1, 16));
            update.set("status", ExtractionTaskStatus.PENDING)
                    .set("nextAttemptAt", Instant.now().plusMillis(backoffMillis));
        } else {
            update.set("status", ExtractionTaskStatus.DEAD);
        }
        mongo.updateFirst(new Query(leasedBy(taskId, owner)), update, ExtractionTask.class);
        return retry;
    }

    private Criteria leasedBy(String taskId, String owner) {
        return new Criteria().andOperator(
                Criteria.where("_id").is(taskId),
                Criteria.where("status").is(ExtractionTaskStatus.LEASED),
                Criteria.where("leaseOwner").is(owner));
    }

    @Override
    public int requeueExpired(Instant now) {
        return (int) mongo.updateMulti(
                new Query(new Criteria().andOperator(
                        Criteria.where("status").is(ExtractionTaskStatus.LEASED),
                        Criteria.where("leaseExpiresAt").lt(now))),
                new Update()
                        .set("status", ExtractionTaskStatus.PENDING)
                        .unset("leaseOwner")
                        .unset("leaseExpiresAt")
                        .set("updatedDate", now),
                ExtractionTask.class).getModifiedCount();
    }

    @Override
    public Map<ExtractionTaskStatus, Long> countByStatus(String jobId) {
        Map<ExtractionTaskStatus, Long> counts = new EnumMap<>(ExtractionTaskStatus.class);
        for (ExtractionTaskStatus status : ExtractionTaskStatus.values()) {
            long count = mongo.count(new Query(new Criteria().andOperator(
                    Criteria.where("jobId").is(jobId),
                    Criteria.where("status").is(status))), ExtractionTask.class);
            if (count > 0) {
                counts.put(status, count);
            }
        }
        return counts;
    }

    @Override
    public long depth() {
        Instant now = Instant.now();
        return mongo.count(new Query(new Criteria().andOperator(
                Criteria.where("status").is(ExtractionTaskStatus.PENDING),
                Criteria.where("nextAttemptAt").lte(now))), ExtractionTask.class);
    }

    @Override
    public List<ExtractionTask> listByJob(String jobId, int limit) {
        return mongo.find(new Query(Criteria.where("jobId").is(jobId))
                .with(Sort.by("_id")).limit(limit), ExtractionTask.class);
    }

    @Override
    public Optional<ExtractionTask> get(String taskId) {
        return Optional.ofNullable(mongo.findById(taskId, ExtractionTask.class));
    }
}
