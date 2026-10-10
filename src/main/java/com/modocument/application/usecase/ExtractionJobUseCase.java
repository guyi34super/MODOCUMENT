package com.modocument.application.usecase;

import com.modocument.application.port.Connector;
import com.modocument.application.port.ConnectorStateStore;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.JobSecrets;
import com.modocument.application.port.TaskEventBus;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Lifecycle of distributed extraction jobs. Starting a job plans the seed
 * tasks and publishes wake events; workers do the rest. Resume re-announces
 * work (unfinished tasks are still in the queue) — dedup keys make repeated
 * planning idempotent, so nothing is lost or extracted twice.
 */
@Service
public class ExtractionJobUseCase {

    private final ExtractionSourceStore sources;
    private final ExtractionJobStore jobs;
    private final ExtractionTaskQueue queue;
    private final TaskEventBus eventBus;
    private final ConnectorStateStore states;
    private final JobSecrets secrets;
    private final List<Connector> connectors;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final CurrentUserProvider currentUser;

    public ExtractionJobUseCase(ExtractionSourceStore sources,
                                ExtractionJobStore jobs,
                                ExtractionTaskQueue queue,
                                TaskEventBus eventBus,
                                ConnectorStateStore states,
                                JobSecrets secrets,
                                List<Connector> connectors,
                                TemplateRepository templates,
                                TemplateFieldsRepository templateFields,
                                CurrentUserProvider currentUser) {
        this.sources = sources;
        this.jobs = jobs;
        this.queue = queue;
        this.eventBus = eventBus;
        this.states = states;
        this.secrets = secrets;
        this.connectors = connectors;
        this.templates = templates;
        this.templateFields = templateFields;
        this.currentUser = currentUser;
    }

    /**
     * Creates a job. Per-run credentials (DB username/password, API token)
     * are held in process memory only and removed when the job finishes.
     */
    public ExtractionJob create(String sourceId, Integer templateId, String dataSpec,
                                boolean incremental, Map<String, String> credentials) {
        // requireSource is owner-scoped, so a caller cannot start a job against
        // another user's source.
        ExtractionSource source = requireSource(sourceId);
        if (!source.isTermsAcknowledged()) {
            throw new IllegalStateException(
                    "Source " + sourceId + " has not acknowledged the source's terms of use.");
        }
        Integer effectiveTemplateId = templateId != null ? templateId : source.getTemplateId();
        if (templateId != null) {
            // The template must exist AND be usable by the caller (own, or
            // shared with their bank — ws22 read/use rule) — otherwise a user
            // could stamp their own jobs against someone else's private
            // template. (The source's own templateId is re-checked below.)
            templates.getAccessible(templateId, currentUser.requireUserId(),
                    currentUser.requireBankId()).orElseThrow(
                    () -> new NoSuchElementException("Template " + templateId + " not found."));
        } else if (effectiveTemplateId != null
                && templates.getAccessible(effectiveTemplateId, currentUser.requireUserId(),
                currentUser.requireBankId()).isEmpty()) {
            // ws22: the source's template was usable when the source was saved
            // but no longer is (made private again, or deleted): the job runs
            // without it rather than deriving a spec from fields the caller
            // may not read any more.
            effectiveTemplateId = null;
        }
        ExtractionJob job = new ExtractionJob();
        job.setOwnerUserId(currentUser.requireUserId());
        job.setBankId(currentUser.requireBankId());
        job.setSourceId(sourceId);
        job.setTemplateId(effectiveTemplateId);
        job.setDataSpec(effectiveDataSpec(dataSpec, source, effectiveTemplateId));
        job.setIncremental(incremental);
        job.setStatus(ExtractionJobStatus.PENDING);
        job = jobs.save(job);
        if (credentials != null && !credentials.isEmpty()) {
            secrets.put(job.getId(), credentials);
        }
        return job;
    }

    /**
     * Request text wins, then the source's text, then a spec derived from the
     * template's current fields — so each new job picks up template edits.
     */
    private String effectiveDataSpec(String dataSpec, ExtractionSource source,
                                     Integer templateId) {
        if (dataSpec != null && !dataSpec.isBlank()) {
            return dataSpec;
        }
        if (source.getDataSpec() != null && !source.getDataSpec().isBlank()) {
            return source.getDataSpec();
        }
        return templateId != null ? dataSpecFromTemplate(templateId) : null;
    }

    private String dataSpecFromTemplate(int templateId) {
        List<TemplateFields> fields = templateFields.listByTemplateId(templateId);
        if (fields.isEmpty()) {
            return null;
        }
        return fields.stream()
                .map(field -> field.getTemplateDataType() == null
                        || field.getTemplateDataType().getDataType() == null
                        ? field.getFieldName()
                        : field.getFieldName()
                                + " (" + field.getTemplateDataType().getDataType() + ")")
                .collect(Collectors.joining(", "));
    }

    /** Plans the seed tasks and sets the workers loose. */
    public ExtractionJob start(String jobId) {
        ExtractionJob job = get(jobId);
        if (job.getStatus() != ExtractionJobStatus.PENDING) {
            throw new IllegalStateException("Job " + jobId + " has already been started.");
        }
        ExtractionSource source = requireSource(job.getSourceId());
        ConnectorState state = states.get(source.getId()).orElse(null);
        List<ExtractionTask> seeds = connectorFor(source).seedTasks(job, source, state);
        if (seeds.isEmpty()) {
            throw new IllegalStateException("Source " + source.getId()
                    + " produced no work (check its seed URLs / endpoint / table).");
        }
        for (ExtractionTask seed : seeds) {
            queue.enqueue(seed);
        }
        job.setStatus(ExtractionJobStatus.RUNNING);
        job = jobs.save(job);
        eventBus.publishTaskAvailable(jobId);
        return job;
    }

    /** Workers finish their in-flight task, then leave the rest queued. */
    public ExtractionJob pause(String jobId) {
        ExtractionJob job = get(jobId);
        if (job.getStatus() != ExtractionJobStatus.RUNNING) {
            throw new IllegalStateException("Only a running job can be paused.");
        }
        job.setStatus(ExtractionJobStatus.PAUSED);
        return jobs.save(job);
    }

    public ExtractionJob resume(String jobId) {
        ExtractionJob job = get(jobId);
        if (job.getStatus() == ExtractionJobStatus.PENDING) {
            return start(jobId);
        }
        if (job.getStatus() != ExtractionJobStatus.PAUSED
                && job.getStatus() != ExtractionJobStatus.FAILED) {
            throw new IllegalStateException("Only a paused or failed job can be resumed.");
        }
        job.setStatus(ExtractionJobStatus.RUNNING);
        job = jobs.save(job);
        eventBus.publishTaskAvailable(jobId);
        return job;
    }

    public ExtractionJob cancel(String jobId) {
        ExtractionJob job = get(jobId);
        job.setStatus(ExtractionJobStatus.CANCELLED);
        job = jobs.save(job);
        secrets.remove(jobId);
        return job;
    }

    public ExtractionJob get(String jobId) {
        // Owner-scoped: every lifecycle method (start/pause/resume/cancel/
        // taskCounts) routes through here, and the controller gates records/
        // audit/bridge/infer/summary on it too, so a cross-user job is a 404.
        return jobs.getByIdAndOwner(jobId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Extraction job " + jobId + " not found."));
    }

    public List<ExtractionJob> list() {
        return jobs.listByOwner(currentUser.requireUserId());
    }

    public Map<ExtractionTaskStatus, Long> taskCounts(String jobId) {
        get(jobId);
        return queue.countByStatus(jobId);
    }

    private ExtractionSource requireSource(String sourceId) {
        return sources.getByIdAndOwner(sourceId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Extraction source " + sourceId + " not found."));
    }

    private Connector connectorFor(ExtractionSource source) {
        return connectors.stream()
                .filter(c -> c.type() == source.getType())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No connector registered for source type " + source.getType() + "."));
    }
}
