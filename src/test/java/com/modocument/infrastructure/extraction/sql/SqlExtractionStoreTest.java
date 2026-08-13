package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ConnectorStateStore;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL extraction store against the H2 test schema (built from the JPA
 * entities): the persistence guarantees the in-memory/Mongo adapters give —
 * owner scoping, atomic counters, dedup constraints, cursor paging — must
 * hold here too, because extraction.store=sql is the production default.
 */
@SpringBootTest(properties = "extraction.store=sql")
@ActiveProfiles("test")
class SqlExtractionStoreTest {

    private static final int ALICE = 1;
    private static final int BOB = 2;

    @Autowired
    private ExtractionSourceStore sources;
    @Autowired
    private ExtractionJobStore jobs;
    @Autowired
    private RawRecordStore rawRecords;
    @Autowired
    private ProcessedRecordStore processedRecords;
    @Autowired
    private ExtractionAuditLog audit;
    @Autowired
    private ConnectorStateStore connectorState;
    @Autowired
    private SqlExtractionStartupRecovery recovery;

    private ExtractionSource newSource(int owner) {
        ExtractionSource source = new ExtractionSource();
        source.setOwnerUserId(owner);
        source.setName("Docs");
        source.setType(SourceType.WEBSITE);
        source.setTermsAcknowledged(true);
        source.setTemplateId(7);
        WebSourceConfig web = new WebSourceConfig();
        web.setSeedUrls(List.of("https://docs.example.com/start"));
        web.setAllowedDomains(List.of("example.com"));
        web.setMaxDepth(2);
        source.setWeb(web);
        return source;
    }

    private ExtractionJob newJob(ExtractionJobStatus status) {
        ExtractionJob job = new ExtractionJob();
        job.setOwnerUserId(ALICE);
        job.setSourceId("src-1");
        job.setStatus(status);
        return job;
    }

    @Test
    void sourcesSurviveRoundTripAndStayOwnerScoped() {
        ExtractionSource saved = sources.save(newSource(ALICE));

        ExtractionSource loaded = sources.getByIdAndOwner(saved.getId(), ALICE).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Docs");
        assertThat(loaded.getTemplateId()).isEqualTo(7);
        assertThat(loaded.getWeb().getSeedUrls()).containsExactly("https://docs.example.com/start");
        assertThat(loaded.getWeb().getMaxDepth()).isEqualTo(2);
        assertThat(loaded.getApi()).isNull();
        assertThat(loaded.getCreatedDate()).isNotNull();

        // Another user cannot see it; the owner-scoped list can.
        assertThat(sources.getByIdAndOwner(saved.getId(), BOB)).isEmpty();
        assertThat(sources.listByOwner(BOB)).noneMatch(s -> s.getId().equals(saved.getId()));
        assertThat(sources.listByOwner(ALICE)).anyMatch(s -> s.getId().equals(saved.getId()));

        // Updating keeps the id, deleting removes the row.
        loaded.setDataSpec("names and emails");
        sources.save(loaded);
        assertThat(sources.getById(saved.getId()).orElseThrow().getDataSpec())
                .isEqualTo("names and emails");
        sources.delete(saved.getId());
        assertThat(sources.getById(saved.getId())).isEmpty();
    }

    @Test
    void jobCountersSurviveConcurrentIncrements() throws InterruptedException {
        ExtractionJob job = jobs.save(newJob(ExtractionJobStatus.RUNNING));

        int threads = 4;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        jobs.increment(job.getId(), ExtractionJob.RECORDS_RAW, 1);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        ExtractionJob loaded = jobs.getByIdAndOwner(job.getId(), ALICE).orElseThrow();
        assertThat(loaded.getCounters())
                .containsEntry(ExtractionJob.RECORDS_RAW, (long) threads * perThread);

        // A later save must not clobber the counters.
        loaded.setStatus(ExtractionJobStatus.COMPLETED);
        jobs.save(loaded);
        assertThat(jobs.getById(job.getId()).orElseThrow().getCounters())
                .containsEntry(ExtractionJob.RECORDS_RAW, (long) threads * perThread);
    }

    @Test
    void rawRecordsDeduplicateOnSourceAndContentHash() {
        RawRecord first = new RawRecord();
        first.setJobId("job-raw");
        first.setSourceId("src-raw");
        first.setUri("https://example.com/a");
        first.setBody("<html>same</html>");
        first.setContentHash("hash-1");
        first.setFetchedAt(Instant.now());
        assertThat(rawRecords.saveIfNew(first)).isTrue();

        RawRecord duplicate = new RawRecord();
        duplicate.setJobId("job-raw");
        duplicate.setSourceId("src-raw");
        duplicate.setUri("https://example.com/a?again");
        duplicate.setBody("<html>same</html>");
        duplicate.setContentHash("hash-1");
        duplicate.setFetchedAt(Instant.now());
        assertThat(rawRecords.saveIfNew(duplicate)).isFalse();

        assertThat(rawRecords.countByJob("job-raw")).isEqualTo(1);
    }

    @Test
    void processedRecordsDeduplicateAndPageInIdOrder() {
        ProcessedRecord a = record("job-proc", "key-a", Map.of("Name", "Ada"));
        ProcessedRecord b = record("job-proc", "key-b", Map.of("Name", "Bob"));
        ProcessedRecord aAgain = record("job-proc", "key-a", Map.of("Name", "Ada"));
        assertThat(processedRecords.saveAllIfNew(List.of(a, b, aAgain))).isEqualTo(2);
        assertThat(processedRecords.countByJob("job-proc")).isEqualTo(2);

        List<ProcessedRecord> firstPage = processedRecords.findBatch("job-proc", null, 1);
        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getFields()).containsEntry("Name", "Ada");

        List<ProcessedRecord> secondPage =
                processedRecords.findBatch("job-proc", firstPage.get(0).getId(), 10);
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).getFields()).containsEntry("Name", "Bob");
    }

    @Test
    void auditTrailPagesByCursor() {
        for (int i = 1; i <= 3; i++) {
            audit.add(ExtractionAuditEvent.of("job-audit", "src-audit", AuditAction.FETCH,
                    "https://example.com/" + i, "ok", "fetch " + i));
        }
        List<ExtractionAuditEvent> firstTwo = audit.findByJob("job-audit", null, 2);
        assertThat(firstTwo).hasSize(2);
        assertThat(firstTwo.get(0).getUri()).endsWith("/1");

        List<ExtractionAuditEvent> rest =
                audit.findByJob("job-audit", firstTwo.get(1).getId(), 10);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).getUri()).endsWith("/3");
    }

    @Test
    void connectorStateUpserts() {
        ConnectorState state = new ConnectorState();
        state.setSourceId("src-state");
        state.getEtags().put("etag:https://example.com/", "\"v1\"");
        state.setLastCursor("cursor-1");
        connectorState.save(state);

        ConnectorState loaded = connectorState.get("src-state").orElseThrow();
        assertThat(loaded.getEtags()).containsEntry("etag:https://example.com/", "\"v1\"");
        assertThat(loaded.getLastCursor()).isEqualTo("cursor-1");

        loaded.setLastCursor("cursor-2");
        connectorState.save(loaded);
        assertThat(connectorState.get("src-state").orElseThrow().getLastCursor())
                .isEqualTo("cursor-2");
    }

    @Test
    void startupRecoveryFailsJobsThatWereMidRun() {
        ExtractionJob running = jobs.save(newJob(ExtractionJobStatus.RUNNING));
        ExtractionJob paused = jobs.save(newJob(ExtractionJobStatus.PAUSED));
        ExtractionJob pending = jobs.save(newJob(ExtractionJobStatus.PENDING));
        ExtractionJob completed = jobs.save(newJob(ExtractionJobStatus.COMPLETED));

        recovery.run(null);

        assertThat(jobs.getById(running.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionJobStatus.FAILED);
        assertThat(jobs.getById(running.getId()).orElseThrow().getErrorSummary())
                .isEqualTo(SqlExtractionStartupRecovery.INTERRUPTED_SUMMARY);
        assertThat(jobs.getById(paused.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionJobStatus.FAILED);
        assertThat(jobs.getById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionJobStatus.PENDING);
        assertThat(jobs.getById(completed.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionJobStatus.COMPLETED);
    }

    private static ProcessedRecord record(String jobId, String dedupKey, Map<String, String> fields) {
        ProcessedRecord record = new ProcessedRecord();
        record.setJobId(jobId);
        record.setDedupKey(dedupKey);
        record.getFields().putAll(fields);
        record.setProcessedAt(Instant.now());
        return record;
    }
}
