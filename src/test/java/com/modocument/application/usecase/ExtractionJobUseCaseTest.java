package com.modocument.application.usecase;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.port.Connector;
import com.modocument.application.port.TaskEventBus;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskType;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.InProcessJobSecrets;
import com.modocument.infrastructure.extraction.memory.InMemoryConnectorStateStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionJobStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionSourceStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionTaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionJobUseCaseTest {

    private final InMemoryExtractionSourceStore sources = new InMemoryExtractionSourceStore();
    private final InMemoryExtractionJobStore jobs = new InMemoryExtractionJobStore();
    private final InMemoryExtractionTaskQueue queue = new InMemoryExtractionTaskQueue();
    private final TaskEventBus eventBus = mock(TaskEventBus.class);
    private final InProcessJobSecrets secrets = new InProcessJobSecrets();
    private final Connector connector = mock(Connector.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository templateFields = mock(TemplateFieldsRepository.class);

    private ExtractionJobUseCase useCase;
    private ExtractionSource source;

    private static final int USER = 1;

    @BeforeEach
    void setUp() {
        when(connector.type()).thenReturn(SourceType.WEBSITE);
        useCase = new ExtractionJobUseCase(sources, jobs, queue, eventBus,
                new InMemoryConnectorStateStore(), secrets, List.of(connector),
                templates, templateFields, FixedUser.provider(USER));

        source = new ExtractionSource();
        source.setOwnerUserId(USER);
        source.setType(SourceType.WEBSITE);
        source.setTermsAcknowledged(true);
        source = sources.save(source);
    }

    private ExtractionTask seed() {
        ExtractionTask task = new ExtractionTask();
        task.setJobId("ignored-until-enqueued");
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.FETCH_URL);
        task.setDedupKey("url:a");
        task.setMaxAttempts(3);
        return task;
    }

    @Test
    void createRejectsUnknownSourceAndUnacknowledgedTerms() {
        assertThatThrownBy(() -> useCase.create("missing", null, null, false, null))
                .isInstanceOf(NoSuchElementException.class);

        source.setTermsAcknowledged(false);
        sources.save(source);
        assertThatThrownBy(() -> useCase.create(source.getId(), null, null, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms");
    }

    private TemplateFields field(String name, String dataType) {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        if (dataType != null) {
            field.setTemplateDataType(new TemplateDataType(1, dataType));
        }
        return field;
    }

    private void templateFiveUsable() {
        when(templates.getAccessible(5, USER, com.modocument.domain.entity.Bank.DEFAULT_BANK_ID))
                .thenReturn(Optional.of(new Template(5, "Staff", null)));
    }

    @Test
    void createInheritsSourceTemplateAndDerivesDataSpecFromItsFields() {
        templateFiveUsable();
        source.setTemplateId(5);
        sources.save(source);
        when(templateFields.listByTemplateId(5)).thenReturn(List.of(
                field("EmployeeId", "Text"), field("HireDate", "Date"), field("Notes", null)));

        ExtractionJob job = useCase.create(source.getId(), null, null, false, null);

        assertThat(job.getTemplateId()).isEqualTo(5);
        assertThat(job.getDataSpec())
                .isEqualTo("EmployeeId (Text), HireDate (Date), Notes");
    }

    /** ws22: the source's (once shared) template is no longer readable by the caller. */
    @Test
    void createDropsAnInheritedTemplateTheCallerCanNoLongerUse() {
        source.setTemplateId(5);
        sources.save(source);
        when(templateFields.listByTemplateId(5)).thenReturn(List.of(field("Secret", "Text")));

        ExtractionJob job = useCase.create(source.getId(), null, null, false, null);

        assertThat(job.getTemplateId()).isNull();
        assertThat(job.getDataSpec()).isNull();
    }

    @Test
    void createPrefersExplicitDataSpecTextOverTemplateDerivation() {
        templateFiveUsable();
        source.setTemplateId(5);
        sources.save(source);

        ExtractionJob job = useCase.create(source.getId(), null, "just the prices", false, null);
        assertThat(job.getDataSpec()).isEqualTo("just the prices");

        source.setDataSpec("names and emails");
        sources.save(source);
        job = useCase.create(source.getId(), null, null, false, null);
        assertThat(job.getDataSpec()).isEqualTo("names and emails");
    }

    @Test
    void createChecksRequestTemplateOwnershipAndDerivesItsSpec() {
        when(templates.getAccessible(9, USER, com.modocument.domain.entity.Bank.DEFAULT_BANK_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.create(source.getId(), 9, null, false, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Template 9");

        when(templates.getAccessible(9, USER, com.modocument.domain.entity.Bank.DEFAULT_BANK_ID))
                .thenReturn(Optional.of(new Template(9, "Products", null)));
        when(templateFields.listByTemplateId(9)).thenReturn(List.of(field("Sku", "Text")));

        ExtractionJob job = useCase.create(source.getId(), 9, null, false, null);
        assertThat(job.getTemplateId()).isEqualTo(9);
        assertThat(job.getDataSpec()).isEqualTo("Sku (Text)");
    }

    @Test
    void createHoldsCredentialsInMemoryOnly() {
        ExtractionJob job = useCase.create(source.getId(), null, null, false,
                Map.of("dbUsername", "sa", "dbPassword", "secret"));

        assertThat(secrets.get(job.getId())).contains(
                Map.of("dbUsername", "sa", "dbPassword", "secret"));
        // Nothing persisted on the job document itself.
        assertThat(jobs.getById(job.getId()).orElseThrow().getCounters()).isEmpty();
    }

    @Test
    void startEnqueuesSeedsAndAnnouncesWork() {
        ExtractionTask task = seed();
        when(connector.seedTasks(any(), any(), any())).thenAnswer(invocation -> {
            ExtractionJob job = invocation.getArgument(0);
            task.setJobId(job.getId());
            return List.of(task);
        });

        ExtractionJob job = useCase.create(source.getId(), null, null, false, null);
        job = useCase.start(job.getId());

        assertThat(job.getStatus()).isEqualTo(ExtractionJobStatus.RUNNING);
        assertThat(queue.depth()).isEqualTo(1);
        verify(eventBus).publishTaskAvailable(job.getId());
        // A second start is rejected.
        String jobId = job.getId();
        assertThatThrownBy(() -> useCase.start(jobId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pauseResumeAndCancelTransitionCorrectly() {
        when(connector.seedTasks(any(), any(), any())).thenAnswer(invocation -> {
            ExtractionTask task = seed();
            task.setJobId(((ExtractionJob) invocation.getArgument(0)).getId());
            return List.of(task);
        });
        ExtractionJob job = useCase.create(source.getId(), null, null, false,
                Map.of("apiToken", "t"));
        useCase.start(job.getId());

        assertThat(useCase.pause(job.getId()).getStatus()).isEqualTo(ExtractionJobStatus.PAUSED);
        assertThat(useCase.resume(job.getId()).getStatus()).isEqualTo(ExtractionJobStatus.RUNNING);

        assertThat(useCase.cancel(job.getId()).getStatus()).isEqualTo(ExtractionJobStatus.CANCELLED);
        // Cancel drops the in-memory credentials.
        assertThat(secrets.get(job.getId())).isEmpty();
    }
}
