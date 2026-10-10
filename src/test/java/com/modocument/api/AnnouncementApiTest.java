package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.usecase.AnnouncementUseCase;
import com.modocument.domain.entity.Announcement;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AnnouncementJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.release.AnnouncementCache;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws24 announcements over the real HTTP stack (H2): the platform admin
 * publishes, banks receive exactly what is meant for them — by audience
 * bank code and display window, MAINTENANCE never dismissible — and nobody
 * but a platform admin of the platform bank may write. PostgreSQL row level
 * security on top of this is AnnouncementRlsPgIT.
 *
 * <p>Not {@code @Transactional}: the cache reads in its own transaction, so
 * fixtures are committed and removed afterwards (the platform bank stays,
 * see {@link PlatformBankFixture}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AnnouncementJpaRepository announcements;
    @Autowired
    private AnnouncementCache cache;
    @Autowired
    private JwtService jwtService;
    @PersistenceContext
    private EntityManager entityManager;

    private final List<AppUser> createdUsers = new ArrayList<>();
    private final List<Bank> createdBanks = new ArrayList<>();
    private final List<Integer> createdAnnouncements = new ArrayList<>();
    private Bank platform;
    private Bank bankA;
    private Bank bankB;
    private String platformAdmin;
    private String adminA;
    private String userA;
    private String userB;
    private String tag;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = bank("ANA" + tag);
        bankB = bank("ANB" + tag);
        platformAdmin = token(platform, Role.PLATFORM_ADMIN);
        adminA = token(bankA, Role.ADMIN);
        userA = token(bankA, Role.READ_ONLY);
        userB = token(bankB, Role.READ_WRITE);
        cache.invalidate();
    }

    @AfterEach
    void cleanUp() {
        announcements.deleteAllById(createdAnnouncements);
        users.deleteAll(createdUsers);
        banks.deleteAll(createdBanks);
        createdAnnouncements.clear();
        createdUsers.clear();
        createdBanks.clear();
        cache.invalidate();
    }

    @Test
    void banksSeeOnlyAnnouncementsForTheirAudienceNewestFirst() throws Exception {
        Instant now = Instant.now();
        int everyone = publish(request("INFO", "INFO", "For everyone " + tag, null,
                now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofHours(2))));
        int onlyB = publish(request("INFO", "WARNING", "Only bank B " + tag,
                List.of(bankB.getCode()), now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(2))));

        assertThat(titles(active(userA))).containsExactly("For everyone " + tag);
        // Newest start first.
        assertThat(titles(active(userB))).containsExactly("Only bank B " + tag, "For everyone " + tag);
        // The platform admin's own bank is not in B's audience either.
        assertThat(titles(active(platformAdmin))).containsExactly("For everyone " + tag);
        assertThat(onlyB).isNotEqualTo(everyone);
    }

    @Test
    void onlyAnnouncementsInsideTheirWindowAndNotArchivedAreActive() throws Exception {
        Instant now = Instant.now();
        publish(request("INFO", "INFO", "Scheduled " + tag, null,
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))));
        int live = publish(request("INFO", "INFO", "Live " + tag, null,
                now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofHours(2))));
        // An ended one can only exist by time passing; write it directly.
        Announcement ended = new Announcement();
        ended.setBankId(platform.getBankId());
        ended.setKind(AnnouncementKind.INFO);
        ended.setSeverity(AnnouncementSeverity.INFO);
        ended.setTitle("Ended " + tag);
        ended.setBody("");
        ended.setStartsAt(now.minus(Duration.ofHours(3)));
        ended.setEndsAt(now.minus(Duration.ofHours(1)));
        ended.setCreatedAt(now.minus(Duration.ofHours(3)));
        createdAnnouncements.add(announcements.save(ended).getAnnouncementId());
        cache.invalidate();

        assertThat(titles(active(userA))).containsExactly("Live " + tag);

        mvc.perform(delete("/api/platform/announcements/" + live)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isNoContent());
        assertThat(titles(active(userA))).isEmpty();

        // The console still lists all three of ours, with their status.
        JsonNode console = json(mvc.perform(get("/api/platform/announcements")
                .header("Authorization", platformAdmin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("announcements");
        Map<String, String> statusByTitle = new LinkedHashMap<>();
        for (JsonNode node : console) {
            if (node.get("title").asText().endsWith(tag)) {
                statusByTitle.put(node.get("title").asText(), node.get("status").asText());
            }
        }
        assertThat(statusByTitle).containsEntry("Scheduled " + tag, "SCHEDULED")
                .containsEntry("Live " + tag, "ARCHIVED")
                .containsEntry("Ended " + tag, "ENDED");
    }

    @Test
    void maintenanceIsNeverDismissibleAndCarriesItsWindow() throws Exception {
        Instant now = Instant.now();
        // Stored to the second.
        Instant windowStart = now.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        Instant windowEnd = now.plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> maintenance = request("MAINTENANCE", "CRITICAL", "Maintenance " + tag,
                null, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofHours(3)));
        maintenance.put("maintenanceStartsAt", windowStart.toString());
        maintenance.put("maintenanceEndsAt", windowEnd.toString());
        publish(maintenance);
        publish(request("INFO", "INFO", "Info " + tag, null,
                now.minus(Duration.ofMinutes(2)), now.plus(Duration.ofHours(3))));

        JsonNode list = ours(active(userA));
        assertThat(list).hasSize(2);
        JsonNode first = list.get(0);
        assertThat(first.get("kind").asText()).isEqualTo("MAINTENANCE");
        assertThat(first.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(first.get("dismissible").asBoolean()).isFalse();
        assertThat(Instant.parse(first.get("maintenanceStartsAt").asText())).isEqualTo(windowStart);
        assertThat(Instant.parse(first.get("maintenanceEndsAt").asText())).isEqualTo(windowEnd);
        assertThat(list.get(1).get("kind").asText()).isEqualTo("INFO");
        assertThat(list.get(1).get("dismissible").asBoolean()).isTrue();
        // Exactly the contract's fields.
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("id", "kind", "severity", "title", "body", "startsAt",
                "endsAt", "maintenanceStartsAt", "maintenanceEndsAt", "releaseVersion", "dismissible");
    }

    @Test
    void theBodyIsStoredAndServedAsPlainTextVerbatim() throws Exception {
        Instant now = Instant.now();
        String body = "<script>alert('x')</script> & <b>bold</b>";
        publish(request("INFO", "INFO", "Markup " + tag, null, now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)), body));

        JsonNode item = ours(active(userB)).get(0);
        assertThat(item.get("body").asText()).isEqualTo(body);
    }

    @Test
    void onlyThePlatformAdminWritesAndAuditsAnnouncementPublished() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> body = request("INFO", "INFO", "Nope " + tag, null,
                now, now.plus(Duration.ofHours(1)));

        for (String caller : List.of(adminA, userA)) {
            mvc.perform(json(post("/api/platform/announcements"), body).header("Authorization", caller))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/announcements").header("Authorization", caller))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/platform/announcements"), body)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/announcements/active")).andExpect(status().isUnauthorized());

        int id = publish(request("INFO", "INFO", "Mine " + tag, null, now, now.plus(Duration.ofHours(1))));
        for (String caller : List.of(adminA, userB)) {
            mvc.perform(json(put("/api/platform/announcements/" + id), body)
                            .header("Authorization", caller))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/platform/announcements/" + id).header("Authorization", caller))
                    .andExpect(status().isForbidden());
        }

        mvc.perform(json(put("/api/platform/announcements/" + id),
                        request("INFO", "WARNING", "Edited " + tag, null, now, now.plus(Duration.ofHours(2))))
                        .header("Authorization", platformAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited " + tag))
                .andExpect(jsonPath("$.severity").value("WARNING"))
                .andExpect(jsonPath("$.updatedBy").isNumber());
        assertThat(titles(active(userA))).containsExactly("Edited " + tag);

        List<AuditEvent> audits = entityManager.createQuery(
                        "SELECT e FROM AuditEvent e WHERE e.entityType = 'Announcement' AND e.entityId = :id "
                                + "ORDER BY e.occurredAt", AuditEvent.class)
                .setParameter("id", String.valueOf(id))
                .getResultList();
        assertThat(audits).extracting(AuditEvent::getAction)
                .containsExactly(AnnouncementUseCase.PUBLISHED, AnnouncementUseCase.PUBLISHED);
        assertThat(audits).allSatisfy(event -> {
            assertThat(event.getBankId()).isEqualTo(platform.getBankId());
            assertThat(event.getActorRole()).isEqualTo("PLATFORM_ADMIN");
        });
    }

    @Test
    void invalidInputIsA400WithTheContractShapeAndUnknownIdsAre404() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> backwards = request("INFO", "INFO", "Backwards " + tag, null,
                now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(1)));
        mvc.perform(json(post("/api/platform/announcements"), backwards)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_INVALID"))
                .andExpect(jsonPath("$.error").value("The display window must end after it starts."))
                .andExpect(jsonPath("$.message").isString());

        Map<String, Object> maintenanceWithoutWindow = request("MAINTENANCE", "WARNING", "M " + tag,
                null, now, now.plus(Duration.ofHours(1)));
        mvc.perform(json(post("/api/platform/announcements"), maintenanceWithoutWindow)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_INVALID"));

        Map<String, Object> ok = request("INFO", "INFO", "Fine " + tag, null, now, now.plus(Duration.ofHours(1)));
        mvc.perform(json(put("/api/platform/announcements/987654321"), ok)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/platform/announcements/987654321")
                        .header("Authorization", platformAdmin))
                .andExpect(status().isNotFound());

        int id = publish(ok);
        mvc.perform(delete("/api/platform/announcements/" + id).header("Authorization", platformAdmin))
                .andExpect(status().isNoContent());
        mvc.perform(json(put("/api/platform/announcements/" + id), ok)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private Bank bank(String code) {
        Bank bank = banks.save(new Bank(code, "Announcement bank " + code));
        createdBanks.add(bank);
        return bank;
    }

    private String token(Bank bank, Role role) {
        AppUser user = PlatformBankFixture.user(users, bank.getBankId(), role, "ws24-ann");
        createdUsers.add(user);
        return "Bearer " + jwtService.issueToken(user);
    }

    private static Map<String, Object> request(String kind, String severity, String title,
                                               List<String> audience, Instant startsAt, Instant endsAt) {
        return request(kind, severity, title, audience, startsAt, endsAt, "Body of " + title);
    }

    private static Map<String, Object> request(String kind, String severity, String title,
                                               List<String> audience, Instant startsAt, Instant endsAt,
                                               String body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", kind);
        request.put("severity", severity);
        request.put("title", title);
        request.put("body", body);
        request.put("audienceBankCodes", audience);
        request.put("startsAt", startsAt.toString());
        request.put("endsAt", endsAt.toString());
        return request;
    }

    private int publish(Map<String, Object> request) throws Exception {
        String response = mvc.perform(json(post("/api/platform/announcements"), request)
                        .header("Authorization", platformAdmin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = json(response).get("id").asInt();
        createdAnnouncements.add(id);
        return id;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder,
                                                      Map<String, Object> body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }

    private JsonNode active(String caller) throws Exception {
        return json(mvc.perform(get("/api/announcements/active").header("Authorization", caller))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("announcements");
    }

    /** This test's announcements only (another test class may have left a RELEASE banner). */
    private JsonNode ours(JsonNode list) {
        var filtered = MAPPER.createArrayNode();
        for (JsonNode node : list) {
            if (node.get("title").asText().endsWith(tag)) {
                filtered.add(node);
            }
        }
        return filtered;
    }

    private List<String> titles(JsonNode list) {
        List<String> titles = new ArrayList<>();
        for (JsonNode node : ours(list)) {
            titles.add(node.get("title").asText());
        }
        return titles;
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
