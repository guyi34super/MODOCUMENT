package com.modocument.infrastructure.release;

import com.modocument.application.usecase.AnnouncementUseCase;
import com.modocument.domain.entity.Announcement;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.infrastructure.persistence.AnnouncementJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.DeployedVersionJpaRepository;
import com.modocument.infrastructure.release.DeployedVersionRecorder.Outcome;
import com.modocument.testsupport.PlatformBankFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws24 deployment recorder (H2): the first call for a version wins — one
 * DeployedVersion row, one VERSION_DEPLOYED audit row in EVERY bank (each
 * in its own bank), one RELEASE banner owned by the platform bank — and
 * every later call for that version does nothing. Concurrent tasks on
 * PostgreSQL: AnnouncementRlsPgIT.
 *
 * <p>Each test records its own made-up version, so the version this
 * context recorded at startup never interferes.
 */
@SpringBootTest
@AutoConfigureMockMvc // same context configuration as the API tests (one cached context)
@ActiveProfiles("test")
class DeployedVersionRecorderTest {

    @Autowired
    private DeployedVersionRecorder recorder;
    @Autowired
    private DeployedVersionJpaRepository deployments;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AnnouncementJpaRepository announcements;
    @Autowired
    private AnnouncementUseCase announcementUseCase;
    @PersistenceContext
    private EntityManager entityManager;

    private final List<Bank> createdBanks = new ArrayList<>();
    private String version;

    @BeforeEach
    void seed() {
        PlatformBankFixture.ensurePlatformBank(banks);
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        createdBanks.add(banks.save(new Bank("DVA" + tag, "Recorder bank A")));
        createdBanks.add(banks.save(new Bank("DVB" + tag, "Recorder bank B")));
        version = "9.9." + (System.nanoTime() % 100_000) + "-test";
    }

    @AfterEach
    void cleanUp() {
        announcements.deleteAll(releaseBanners());
        deployments.findById(version).ifPresent(deployments::delete);
        banks.deleteAll(createdBanks);
        createdBanks.clear();
    }

    @Test
    void theFirstCallWinsAuditsEveryBankOnceAndAnnouncesTheRelease() {
        assertThat(recorder.record(version)).isEqualTo(Outcome.RECORDED);

        assertThat(deployments.findById(version)).hasValueSatisfying(row -> {
            assertThat(row.getEnvironment()).isEqualTo("local");
            assertThat(row.getDeployedAt()).isNotNull();
        });

        List<Integer> allBanks = banks.findAll().stream().map(Bank::getBankId).toList();
        Map<Integer, Long> auditsPerBank = versionAudits().stream()
                .collect(Collectors.groupingBy(AuditEvent::getBankId, Collectors.counting()));
        assertThat(auditsPerBank.keySet()).containsExactlyInAnyOrderElementsOf(allBanks);
        assertThat(auditsPerBank.values()).allMatch(count -> count == 1);
        assertThat(versionAudits()).allSatisfy(event -> {
            assertThat(event.getEntityType()).isEqualTo("DeployedVersion");
            assertThat(event.getNewValues()).contains("\"version\":\"" + version + "\"")
                    .contains("\"environment\":\"local\"");
            assertThat(event.getActorUserId()).isNull(); // the system, not a person
        });

        List<Announcement> banners = releaseBanners();
        assertThat(banners).hasSize(1);
        Announcement banner = banners.get(0);
        assertThat(banner.getBankId()).isEqualTo(PlatformBankFixture.ensurePlatformBank(banks).getBankId());
        assertThat(banner.getAudienceBankCodes()).isNull(); // every bank
        assertThat(banner.getTitle()).contains(version);
        assertThat(banner.getEndsAt()).isAfter(banner.getStartsAt());

        // Served to every bank through the cache, dismissible.
        assertThat(announcementUseCase.activeFor(createdBanks.get(0).getBankId()))
                .anySatisfy(view -> {
                    assertThat(view.kind()).isEqualTo(AnnouncementKind.RELEASE);
                    assertThat(view.releaseVersion()).isEqualTo(version);
                    assertThat(view.dismissible()).isTrue();
                });
    }

    @Test
    void everyLaterCallForTheSameVersionDoesNothing() {
        assertThat(recorder.record(version)).isEqualTo(Outcome.RECORDED);
        int audits = versionAudits().size();

        assertThat(recorder.record(version)).isEqualTo(Outcome.ALREADY_RECORDED);
        assertThat(recorder.record(version)).isEqualTo(Outcome.ALREADY_RECORDED);

        assertThat(versionAudits()).hasSize(audits);
        assertThat(releaseBanners()).hasSize(1);
        assertThat(recorder.claim(version, "abc1234", Instant.now(), "local")).isFalse();
    }

    @Test
    void noVersionMeansNothingToRecord() {
        assertThat(recorder.record(null)).isEqualTo(Outcome.SKIPPED);
        assertThat(recorder.record(" ")).isEqualTo(Outcome.SKIPPED);
    }

    private List<AuditEvent> versionAudits() {
        return entityManager.createQuery("SELECT e FROM AuditEvent e WHERE e.action = :action "
                        + "AND e.entityId = :version", AuditEvent.class)
                .setParameter("action", DeployedVersionRecorder.VERSION_DEPLOYED)
                .setParameter("version", version)
                .getResultList();
    }

    private List<Announcement> releaseBanners() {
        return announcements.findAll().stream()
                .filter(a -> a.getKind() == AnnouncementKind.RELEASE && version.equals(a.getReleaseVersion()))
                .toList();
    }
}
