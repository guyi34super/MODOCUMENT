package com.modocument.application.usecase;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.application.port.Connector;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TaskEventBus;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.InProcessJobSecrets;
import com.modocument.infrastructure.extraction.memory.InMemoryConnectorStateStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionJobStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionSourceStore;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionTaskQueue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extraction lives on its own Mongo/in-memory stack, so it needs the same
 * owner scoping as the JPA domain: one user must never see or act on another
 * user's sources or jobs. Both use cases run against the same shared stores
 * with a different {@link CurrentUserProvider} per user, mirroring two callers.
 */
class ExtractionCrossUserAccessTest {

    private static final int ALICE = 101;
    private static final int BOB = 202;

    private final InMemoryExtractionSourceStore sources = new InMemoryExtractionSourceStore();
    private final InMemoryExtractionJobStore jobs = new InMemoryExtractionJobStore();
    private final InMemoryExtractionTaskQueue queue = new InMemoryExtractionTaskQueue();
    private final InProcessJobSecrets secrets = new InProcessJobSecrets();

    private final TemplateRepository templates = mock(TemplateRepository.class);

    private ExtractionSourceUseCase sourcesFor(int user) {
        return new ExtractionSourceUseCase(sources, templates, FixedUser.provider(user));
    }

    private ExtractionJobUseCase jobsFor(int user) {
        Connector connector = mock(Connector.class);
        CurrentUserProvider provider = FixedUser.provider(user);
        return new ExtractionJobUseCase(sources, jobs, queue, mock(TaskEventBus.class),
                new InMemoryConnectorStateStore(), secrets, List.of(connector),
                templates, mock(TemplateFieldsRepository.class), provider);
    }

    private SaveExtractionSourceRequest websiteRequest() {
        SaveExtractionSourceRequest request = new SaveExtractionSourceRequest();
        request.setName("Docs");
        request.setType(SourceType.WEBSITE);
        request.setTermsAcknowledged(true);
        WebSourceConfig web = new WebSourceConfig();
        web.setSeedUrls(List.of("https://docs.example.com/start"));
        web.setAllowedDomains(List.of("example.com"));
        request.setWeb(web);
        return request;
    }

    @Test
    void aSourceIsInvisibleToAnotherUser() {
        ExtractionSource alices = sourcesFor(ALICE).create(websiteRequest());

        assertThat(sourcesFor(ALICE).get(alices.getId())).isNotNull();
        assertThat(sourcesFor(ALICE).list()).extracting(ExtractionSource::getId).contains(alices.getId());

        assertThatThrownBy(() -> sourcesFor(BOB).get(alices.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(sourcesFor(BOB).list()).isEmpty();
    }

    @Test
    void bobCannotStartAJobAgainstAlicesSource() {
        ExtractionSource alices = sourcesFor(ALICE).create(websiteRequest());

        // Bob references Alice's source id directly — the owner-scoped source
        // lookup makes it look like it does not exist.
        assertThatThrownBy(() -> jobsFor(BOB).create(alices.getId(), null, null, false, null))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void bobCannotReferenceAlicesTemplate() {
        // Template 7 belongs to Alice only — the owner-scoped lookup returns
        // empty for Bob, on the source and on the job alike.
        when(templates.getAccessible(7, ALICE, com.modocument.domain.entity.Bank.DEFAULT_BANK_ID))
                .thenReturn(java.util.Optional.of(new Template(7, "Employees", null)));

        SaveExtractionSourceRequest request = websiteRequest();
        request.setTemplateId(7);
        assertThat(sourcesFor(ALICE).create(request).getTemplateId()).isEqualTo(7);
        assertThatThrownBy(() -> sourcesFor(BOB).create(request))
                .isInstanceOf(java.util.NoSuchElementException.class);

        ExtractionSource bobs = sourcesFor(BOB).create(websiteRequest());
        assertThatThrownBy(() -> jobsFor(BOB).create(bobs.getId(), 7, null, false, null))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aJobIsInvisibleToAnotherUser() {
        ExtractionSource alices = sourcesFor(ALICE).create(websiteRequest());
        ExtractionJob job = jobsFor(ALICE).create(alices.getId(), null, null, false, null);

        assertThat(jobsFor(ALICE).get(job.getId())).isNotNull();
        assertThat(jobsFor(ALICE).list()).extracting(ExtractionJob::getId).contains(job.getId());

        assertThatThrownBy(() -> jobsFor(BOB).get(job.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(jobsFor(BOB).list()).isEmpty();
    }
}
