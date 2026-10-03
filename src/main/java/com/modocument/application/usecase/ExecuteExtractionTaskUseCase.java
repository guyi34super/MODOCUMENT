package com.modocument.application.usecase;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.Connector;
import com.modocument.application.port.ConnectorStateStore;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.ExtractionTaskRunner;
import com.modocument.application.port.JobSecrets;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.RawRecordStore;
import com.modocument.application.port.TaskEventBus;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The worker's unit of work: run the claimed task through its connector,
 * persist raw records (content-hash dedup) and their normalized rows in
 * bounded batches, enqueue discovered follow-up tasks (respecting page caps),
 * merge incremental state, and settle the task — complete, retry with
 * backoff, or dead-letter. Follow-ups are enqueued BEFORE the task is acked
 * so the job can never look finished while it still grows.
 */
@Service
public class ExecuteExtractionTaskUseCase implements ExtractionTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecuteExtractionTaskUseCase.class);

    private final ExtractionJobStore jobs;
    private final ExtractionSourceStore sources;
    private final ExtractionTaskQueue queue;
    private final ConnectorStateStore states;
    private final RawRecordStore rawRecords;
    private final ProcessedRecordStore processedRecords;
    private final ExtractionAuditLog audit;
    private final TaskEventBus eventBus;
    private final JobSecrets secrets;
    private final List<Connector> connectors;
    private final int batchSize;
    private final long backoffMillis;
    private final int maxPagesDefault;

    public ExecuteExtractionTaskUseCase(ExtractionJobStore jobs,
                                        ExtractionSourceStore sources,
                                        ExtractionTaskQueue queue,
                                        ConnectorStateStore states,
                                        RawRecordStore rawRecords,
                                        ProcessedRecordStore processedRecords,
                                        ExtractionAuditLog audit,
                                        TaskEventBus eventBus,
                                        JobSecrets secrets,
                                        List<Connector> connectors,
                                        @Value("${extraction.batch-size:500}") int batchSize,
                                        @Value("${extraction.worker.backoff-millis:5000}") long backoffMillis,
                                        @Value("${extraction.web.max-pages-default:100}") int maxPagesDefault) {
        this.jobs = jobs;
        this.sources = sources;
        this.queue = queue;
        this.states = states;
        this.rawRecords = rawRecords;
        this.processedRecords = processedRecords;
        this.audit = audit;
        this.eventBus = eventBus;
        this.secrets = secrets;
        this.connectors = connectors;
        this.batchSize = batchSize;
        this.backoffMillis = backoffMillis;
        this.maxPagesDefault = maxPagesDefault;
    }

    @Override
    public void run(ExtractionTask task, String workerId) {
        // runAsSystem: a queued task carries no tenant, so resolving WHOSE
        // job it is — and the skip/release bookkeeping on it — is a
        // deliberate cross-tenant read; without it row-level security would
        // hide every job from the worker (see the call-site registry in
        // TenantContext). The actual work adopts the job's own bank below.
        TenantContext.runAsSystem(() -> dispatch(task, workerId));
    }

    private void dispatch(ExtractionTask task, String workerId) {
        ExtractionJob job = jobs.getById(task.getJobId()).orElse(null);
        ExtractionSource source = job == null ? null
                : sources.getById(task.getSourceId()).orElse(null);
        if (job == null || source == null || job.getStatus() == ExtractionJobStatus.CANCELLED) {
            queue.complete(task.getId(), workerId, ExtractionTaskStatus.SKIPPED);
            if (job != null) {
                jobs.increment(job.getId(), ExtractionJob.TASKS_SKIPPED, 1);
                settleIfFinished(job.getId());
            }
            return;
        }
        if (job.getStatus() == ExtractionJobStatus.PAUSED) {
            queue.release(task.getId(), workerId);
            return;
        }
        // Worker threads carry no request context, so adopt the job's bank:
        // every row this task writes (raw/processed records, audit events,
        // counters, connector state) is stamped with it, and row-level
        // security scopes every read to it.
        TenantContext.runAs(job.getBankId(), () -> execute(task, workerId, job, source));
    }

    private void execute(ExtractionTask task, String workerId, ExtractionJob job,
                         ExtractionSource source) {
        ConnectorResult result;
        try {
            ConnectorState state = states.get(source.getId()).orElse(null);
            result = connectorFor(source).execute(task, job, source, state);
        } catch (Exception e) {
            handleFailure(task, workerId, job, e);
            return;
        }

        long rawSaved = 0;
        long processedSaved = 0;
        long deduped = 0;
        for (ConnectorResult.FetchedItem item : result.items()) {
            if (!rawRecords.saveIfNew(item.record())) {
                deduped += item.rows().size();
                continue;
            }
            rawSaved++;
            List<ProcessedRecord> batch = new ArrayList<>();
            for (Map<String, String> row : item.rows()) {
                batch.add(toProcessedRecord(job.getId(), item.record().getId(), row));
                if (batch.size() >= batchSize) {
                    int saved = processedRecords.saveAllIfNew(batch);
                    processedSaved += saved;
                    deduped += batch.size() - saved;
                    batch = new ArrayList<>();
                }
            }
            if (!batch.isEmpty()) {
                int saved = processedRecords.saveAllIfNew(batch);
                processedSaved += saved;
                deduped += batch.size() - saved;
            }
        }

        int enqueued = enqueueFollowUps(task, job, source, result.followUps());
        if (!result.stateUpdates().isEmpty()) {
            mergeState(source.getId(), result.stateUpdates());
        }

        queue.complete(task.getId(), workerId, ExtractionTaskStatus.COMPLETED);
        jobs.increment(job.getId(), ExtractionJob.TASKS_COMPLETED, 1);
        if (rawSaved > 0) {
            jobs.increment(job.getId(), ExtractionJob.RECORDS_RAW, rawSaved);
        }
        if (processedSaved > 0) {
            jobs.increment(job.getId(), ExtractionJob.RECORDS_PROCESSED, processedSaved);
        }
        if (deduped > 0) {
            jobs.increment(job.getId(), ExtractionJob.RECORDS_DEDUPED, deduped);
            audit.add(ExtractionAuditEvent.of(job.getId(), source.getId(), AuditAction.DEDUPED,
                    task.getPayload().get("url"), deduped + " duplicate record(s) skipped", null));
        }
        if (enqueued > 0) {
            eventBus.publishTaskAvailable(job.getId());
        }
        settleIfFinished(job.getId());
    }

    private void handleFailure(ExtractionTask task, String workerId, ExtractionJob job, Exception e) {
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        boolean retry = queue.fail(task.getId(), workerId, abbreviate(error),
                Duration.ofMillis(backoffMillis));
        if (retry) {
            audit.add(ExtractionAuditEvent.of(job.getId(), task.getSourceId(), AuditAction.RETRY,
                    task.getPayload().get("url"), "attempt " + task.getAttempts() + " failed",
                    abbreviate(error)));
        } else {
            log.warn("Extraction task {} is dead after {} attempts: {}",
                    task.getId(), task.getAttempts(), error);
            audit.add(ExtractionAuditEvent.of(job.getId(), task.getSourceId(), AuditAction.ERROR,
                    task.getPayload().get("url"), "task dead after " + task.getAttempts()
                            + " attempts", abbreviate(error)));
            jobs.increment(job.getId(), ExtractionJob.TASKS_DEAD, 1);
            eventBus.publishDeadLetter(task.getId(), job.getId());
            settleIfFinished(job.getId());
        }
    }

    private int enqueueFollowUps(ExtractionTask task, ExtractionJob job, ExtractionSource source,
                                 List<ExtractionTask> followUps) {
        if (followUps.isEmpty()) {
            return 0;
        }
        long totalTasks = queue.countByStatus(job.getId()).values().stream()
                .mapToLong(Long::longValue).sum();
        long cap = taskCap(source);
        int enqueued = 0;
        boolean capped = false;
        for (ExtractionTask followUp : followUps) {
            if (totalTasks + enqueued >= cap) {
                capped = true;
                break;
            }
            if (queue.enqueue(followUp)) {
                enqueued++;
            }
        }
        if (capped) {
            audit.add(ExtractionAuditEvent.of(job.getId(), source.getId(), AuditAction.FETCH,
                    task.getPayload().get("url"), "page cap reached",
                    "Frontier capped at " + cap + " pages; raise maxPages to crawl further."));
        }
        return enqueued;
    }

    private long taskCap(ExtractionSource source) {
        if (source.getType() == SourceType.WEBSITE) {
            Integer maxPages = source.getWeb() == null ? null : source.getWeb().getMaxPages();
            return maxPages != null ? maxPages : maxPagesDefault;
        }
        return Long.MAX_VALUE;
    }

    /** Flips the job terminal once nothing is pending or in flight. */
    private void settleIfFinished(String jobId) {
        ExtractionJob job = jobs.getById(jobId).orElse(null);
        if (job == null || (job.getStatus() != ExtractionJobStatus.RUNNING
                && job.getStatus() != ExtractionJobStatus.CANCELLED)) {
            return;
        }
        Map<ExtractionTaskStatus, Long> counts = queue.countByStatus(jobId);
        long open = counts.getOrDefault(ExtractionTaskStatus.PENDING, 0L)
                + counts.getOrDefault(ExtractionTaskStatus.LEASED, 0L)
                + counts.getOrDefault(ExtractionTaskStatus.FAILED, 0L);
        if (open > 0) {
            return;
        }
        secrets.remove(jobId);
        if (job.getStatus() == ExtractionJobStatus.CANCELLED) {
            return;
        }
        long dead = counts.getOrDefault(ExtractionTaskStatus.DEAD, 0L);
        job.setStatus(dead > 0
                ? ExtractionJobStatus.COMPLETED_WITH_ERRORS
                : ExtractionJobStatus.COMPLETED);
        if (dead > 0) {
            job.setErrorSummary(dead + " task(s) exhausted their retries; see the audit log.");
        }
        jobs.save(job);
    }

    private ProcessedRecord toProcessedRecord(String jobId, String rawRecordId,
                                              Map<String, String> row) {
        Map<String, String> fields = new LinkedHashMap<>();
        row.forEach((key, value) -> fields.put(key,
                value == null ? "" : value.trim().replaceAll("\\s+", " ")));
        ProcessedRecord record = new ProcessedRecord();
        record.setJobId(jobId);
        record.setRawRecordId(rawRecordId);
        record.setFields(fields);
        record.setDedupKey(Hashing.sha256(fields.toString()));
        record.setProcessedAt(Instant.now());
        return record;
    }

    private Connector connectorFor(ExtractionSource source) {
        return connectors.stream()
                .filter(c -> c.type() == source.getType())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No connector registered for source type " + source.getType() + "."));
    }

    private void mergeState(String sourceId, Map<String, String> updates) {
        ConnectorState state = states.get(sourceId).orElseGet(() -> new ConnectorState(sourceId));
        for (Map.Entry<String, String> update : updates.entrySet()) {
            String key = update.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("etag:")) {
                state.getEtags().put(key.substring(5), update.getValue());
            } else if (lower.startsWith("lm:")) {
                state.getLastModified().put(key.substring(3), update.getValue());
            } else if (lower.equals("cursor")) {
                state.setLastCursor(update.getValue());
            } else if (lower.equals("watermark")) {
                state.setLastWatermark(update.getValue());
            }
        }
        states.save(state);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
