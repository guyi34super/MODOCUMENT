package com.modocument.application.usecase;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.Connector;
import com.modocument.application.port.TaskEventBus;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.RawRecord;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.ExtractionTaskType;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.InProcessJobSecrets;
import com.modocument.infrastructure.extraction.memory.InMemoryConnectorStateStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionAuditLog;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionJobStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionSourceStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionTaskQueue;
import com.modocument.infrastructure.extraction.memory.InMemoryProcessedRecordStore;
import com.modocument.infrastructure.extraction.memory.InMemoryRawRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecuteExtractionTaskUseCaseTest {

    private final InMemoryExtractionJobStore jobs = new InMemoryExtractionJobStore();
    private final InMemoryExtractionSourceStore sources = new InMemoryExtractionSourceStore();
    private final InMemoryExtractionTaskQueue queue = new InMemoryExtractionTaskQueue();
    private final InMemoryConnectorStateStore states = new InMemoryConnectorStateStore();
    private final InMemoryRawRecordStore rawRecords = new InMemoryRawRecordStore();
    private final InMemoryProcessedRecordStore processedRecords = new InMemoryProcessedRecordStore();
    private final InMemoryExtractionAuditLog audit = new InMemoryExtractionAuditLog();
    private final TaskEventBus eventBus = mock(TaskEventBus.class);
    private final Connector connector = mock(Connector.class);

    private ExecuteExtractionTaskUseCase useCase;
    private ExtractionJob job;
    private ExtractionSource source;

    @BeforeEach
    void setUp() {
        when(connector.type()).thenReturn(SourceType.WEBSITE);
        useCase = new ExecuteExtractionTaskUseCase(jobs, sources, queue, states, rawRecords,
                processedRecords, audit, eventBus, new InProcessJobSecrets(), List.of(connector),
                500, 10, 100);

        source = new ExtractionSource();
        source.setType(SourceType.WEBSITE);
        WebSourceConfig web = new WebSourceConfig();
        web.setMaxPages(3);
        source.setWeb(web);
        source = sources.save(source);

        job = new ExtractionJob();
        job.setSourceId(source.getId());
        job.setStatus(ExtractionJobStatus.RUNNING);
        job = jobs.save(job);
    }

    private ExtractionTask enqueueTask(String dedupKey) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(job.getId());
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.FETCH_URL);
        task.setDedupKey(dedupKey);
        task.setMaxAttempts(2);
        queue.enqueue(task);
        return queue.claim("w1", "n1", 1, Duration.ofMinutes(1)).get(0);
    }

    private RawRecord raw(String uri, String hash) {
        RawRecord record = new RawRecord();
        record.setJobId(job.getId());
        record.setSourceId(source.getId());
        record.setUri(uri);
        record.setBody("<html/>");
        record.setContentHash(hash);
        return record;
    }

    private ExtractionTask followUp(String dedupKey) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(job.getId());
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.FETCH_URL);
        task.setDedupKey(dedupKey);
        task.setMaxAttempts(2);
        return task;
    }

    @Test
    void successStoresRecordsCountersAndCompletesJob() throws Exception {
        ExtractionTask task = enqueueTask("url:a");
        when(connector.execute(any(), any(), any(), any())).thenReturn(new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(raw("https://x/a", "h1"),
                        List.of(Map.of("Name", "Ada"), Map.of("Name", "Alan")))),
                List.of(),
                Map.of("etag:https://x/a", "W/\"1\"")));

        useCase.run(task, "w1");

        assertThat(queue.get(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionTaskStatus.COMPLETED);
        assertThat(rawRecords.countByJob(job.getId())).isEqualTo(1);
        assertThat(processedRecords.countByJob(job.getId())).isEqualTo(2);
        ExtractionJob finished = jobs.getById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ExtractionJobStatus.COMPLETED);
        assertThat(finished.counter(ExtractionJob.RECORDS_PROCESSED)).isEqualTo(2);
        assertThat(states.get(source.getId()).orElseThrow().getEtags())
                .containsEntry("https://x/a", "W/\"1\"");
    }

    @Test
    void duplicateContentIsDedupedNotReprocessed() throws Exception {
        ExtractionTask first = enqueueTask("url:a");
        when(connector.execute(any(), any(), any(), any())).thenReturn(new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(raw("https://x/a", "same-hash"),
                        List.of(Map.of("Name", "Ada")))),
                List.of(), Map.of()));
        useCase.run(first, "w1");

        ExtractionTask second = enqueueTask("url:b");
        when(connector.execute(any(), any(), any(), any())).thenReturn(new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(raw("https://x/b", "same-hash"),
                        List.of(Map.of("Name", "Ada")))),
                List.of(), Map.of()));
        useCase.run(second, "w1");

        assertThat(rawRecords.countByJob(job.getId())).isEqualTo(1);
        assertThat(processedRecords.countByJob(job.getId())).isEqualTo(1);
        assertThat(jobs.getById(job.getId()).orElseThrow()
                .counter(ExtractionJob.RECORDS_DEDUPED)).isEqualTo(1);
    }

    @Test
    void followUpsAreCappedAtMaxPages() throws Exception {
        ExtractionTask task = enqueueTask("url:a");
        when(connector.execute(any(), any(), any(), any())).thenReturn(new ConnectorResult(
                List.of(),
                List.of(followUp("url:b"), followUp("url:c"), followUp("url:d"), followUp("url:e")),
                Map.of()));

        useCase.run(task, "w1");

        // maxPages=3: the original task plus two follow-ups.
        long total = queue.countByStatus(job.getId()).values().stream()
                .mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(3);
        // Job is still running: follow-ups are pending.
        assertThat(jobs.getById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionJobStatus.RUNNING);
    }

    @Test
    void failureRetriesThenDeadLettersAndFinishesWithErrors() throws Exception {
        ExtractionTask task = enqueueTask("url:a");
        when(connector.execute(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("HTTP 503"));

        useCase.run(task, "w1");
        assertThat(queue.get(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionTaskStatus.PENDING);

        // Second (= last) attempt dies.
        Thread.sleep(30);
        ExtractionTask retry = queue.claim("w1", "n1", 1, Duration.ofMinutes(1)).get(0);
        useCase.run(retry, "w1");

        assertThat(queue.get(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionTaskStatus.DEAD);
        ExtractionJob finished = jobs.getById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ExtractionJobStatus.COMPLETED_WITH_ERRORS);
        assertThat(finished.getErrorSummary()).contains("1 task(s)");
    }

    @Test
    void cancelledJobSkipsItsTasks() throws Exception {
        ExtractionTask task = enqueueTask("url:a");
        job.setStatus(ExtractionJobStatus.CANCELLED);
        jobs.save(job);

        useCase.run(task, "w1");

        assertThat(queue.get(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionTaskStatus.SKIPPED);
        assertThat(rawRecords.countByJob(job.getId())).isZero();
    }

    @Test
    void pausedJobReleasesTheTaskForLater() throws Exception {
        ExtractionTask task = enqueueTask("url:a");
        job.setStatus(ExtractionJobStatus.PAUSED);
        jobs.save(job);

        useCase.run(task, "w1");

        ExtractionTask released = queue.get(task.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(ExtractionTaskStatus.PENDING);
        assertThat(released.getAttempts()).isZero();
    }
}
