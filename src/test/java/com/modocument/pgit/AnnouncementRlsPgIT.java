package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.release.AnnouncementCache;
import com.modocument.infrastructure.release.DeployedVersionRecorder;
import com.modocument.infrastructure.release.DeployedVersionRecorder.Outcome;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws24 announcements and the deployment recorder on the REAL PostgreSQL
 * schema with row level security enforced (app booted on
 * {@code modocument_app}):
 * <ul>
 *   <li>the announcement rows belong to the platform bank, so a customer
 *       tenant reading the table directly sees none of them and cannot
 *       write one;</li>
 *   <li>yet every bank receives its banners through
 *       {@code GET /api/announcements/active}, served from the cache that
 *       reads as the platform bank, filtered by audience;</li>
 *   <li>the platform console writes under RLS as the platform bank;</li>
 *   <li>two recorders racing for one version: exactly one wins, every bank
 *       gets exactly one VERSION_DEPLOYED row and one RELEASE banner
 *       exists.</li>
 * </ul>
 */
class AnnouncementRlsPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final int BANK_A = 520;
    private static final int BANK_B = 620;
    private static final int ADMIN_A = 9520;
    private static final int USER_B = 9620;
    private static final int PLATFORM_ADMIN = 9720;
    private static final String TAG = "PgIT ann";

    private static int platformBankId;
    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AnnouncementCache cache;
    @Autowired
    private DeployedVersionRecorder recorder;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            platformBankId = platformBank(owner);
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(" + BANK_A + ", 'ANN_A', 'Announcement Bank A'), "
                    + "(" + BANK_B + ", 'ANN_B', 'Announcement Bank B') ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, Disabled, "
                    + "MustChangePassword) VALUES "
                    + "(" + ADMIN_A + ", 'ann-a-admin@example.com', '{noop}x', 'Ann A Admin', '{}', now(), now(), "
                    + BANK_A + ", 'ADMIN', 0, false, false), "
                    + "(" + USER_B + ", 'ann-b-user@example.com', '{noop}x', 'Ann B User', '{}', now(), now(), "
                    + BANK_B + ", 'READ_ONLY', 0, false, false), "
                    + "(" + PLATFORM_ADMIN + ", 'ann-platform@example.com', '{noop}x', 'Ann Platform', '{}', "
                    + "now(), now(), " + platformBankId + ", 'PLATFORM_ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING");
            String columns = "INSERT INTO Announcement (BankId, Kind, Severity, Title, Body, "
                    + "AudienceBankCodes, StartsAt, EndsAt, MaintenanceStartsAt, MaintenanceEndsAt, "
                    + "Archived, CreatedAt) VALUES ";
            statement.execute(columns
                    + "(" + platformBankId + ", 'INFO', 'INFO', '" + TAG + " everyone', 'b', NULL, "
                    + "now() - interval '1 hour', now() + interval '1 day', NULL, NULL, false, now()), "
                    + "(" + platformBankId + ", 'INFO', 'WARNING', '" + TAG + " only B', 'b', 'ANN_B', "
                    + "now() - interval '1 hour', now() + interval '1 day', NULL, NULL, false, now()), "
                    + "(" + platformBankId + ", 'MAINTENANCE', 'CRITICAL', '" + TAG + " maintenance', 'b', NULL, "
                    + "now() - interval '1 hour', now() + interval '1 day', now() + interval '2 hours', "
                    + "now() + interval '3 hours', false, now()), "
                    + "(" + platformBankId + ", 'INFO', 'INFO', '" + TAG + " archived', 'b', NULL, "
                    + "now() - interval '1 hour', now() + interval '1 day', NULL, NULL, true, now()), "
                    + "(" + platformBankId + ", 'INFO', 'INFO', '" + TAG + " ended', 'b', NULL, "
                    + "now() - interval '2 days', now() - interval '1 day', NULL, NULL, false, now())");
        }
        seeded = true;
    }

    @BeforeEach
    void freshCache() {
        cache.invalidate(); // pick up the rows seeded behind the application's back
    }

    @Test
    void aCustomerTenantCannotReadOrWritePlatformOwnedRowsDirectly() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            try {
                setTenant(app, String.valueOf(BANK_A));
                assertThat(countOurs(app)).isZero();
                assertThatThrownBy(() -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute("INSERT INTO Announcement (BankId, Kind, Severity, Title, "
                                + "Body, StartsAt, EndsAt, CreatedAt) VALUES (" + platformBankId
                                + ", 'INFO', 'INFO', 'forged', 'x', now(), now() + interval '1 day', now())");
                    }
                }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            } finally {
                app.rollback();
            }
            setTenant(app, String.valueOf(platformBankId));
            assertThat(countOurs(app)).isEqualTo(5);
            app.rollback();
        }
    }

    @Test
    void everyBankReceivesItsBannersThroughTheCache() throws Exception {
        Map<String, JsonNode> forA = byTitle(active(ADMIN_A, BANK_A, Role.ADMIN));
        assertThat(forA).containsKeys(TAG + " everyone", TAG + " maintenance")
                .doesNotContainKeys(TAG + " only B", TAG + " archived", TAG + " ended");
        assertThat(forA.get(TAG + " maintenance").get("dismissible").asBoolean()).isFalse();
        assertThat(forA.get(TAG + " everyone").get("dismissible").asBoolean()).isTrue();

        Map<String, JsonNode> forB = byTitle(active(USER_B, BANK_B, Role.READ_ONLY));
        assertThat(forB).containsKeys(TAG + " everyone", TAG + " maintenance", TAG + " only B")
                .doesNotContainKeys(TAG + " archived", TAG + " ended");
    }

    @Test
    void thePlatformConsoleWritesUnderRowLevelSecurity() throws Exception {
        Instant now = Instant.now();
        String body = MAPPER.writeValueAsString(Map.of(
                "kind", "INFO", "severity", "INFO", "title", TAG + " via API",
                "body", "Published through the console",
                "startsAt", now.minus(Duration.ofMinutes(1)).toString(),
                "endsAt", now.plus(Duration.ofHours(1)).toString()));

        mvc.perform(post("/api/platform/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", bearer(ADMIN_A, BANK_A, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization",
                                bearer(PLATFORM_ADMIN, platformBankId, Role.PLATFORM_ADMIN)))
                .andExpect(status().isCreated());

        assertThat(byTitle(active(ADMIN_A, BANK_A, Role.ADMIN))).containsKey(TAG + " via API");
        try (Connection owner = ownerDataSource().getConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT BankId FROM Announcement WHERE Title = ?")) {
            statement.setString(1, TAG + " via API");
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(platformBankId);
            }
        }
    }

    @Test
    void racingRecordersHaveExactlyOneWinner() throws Exception {
        String version = "9.8." + (System.nanoTime() % 100_000) + "-pgit";
        CountDownLatch start = new CountDownLatch(1);
        Callable<Outcome> race = () -> {
            start.await();
            return recorder.record(version);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = pool.submit(race);
            Future<Outcome> second = pool.submit(race);
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(List.of(
                    first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)));
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.RECORDED, Outcome.ALREADY_RECORDED);
        } finally {
            pool.shutdownNow();
        }

        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(single(owner, "SELECT count(*) FROM DeployedVersion WHERE Version = ?", version))
                    .isEqualTo(1);
            Map<Integer, Integer> perBank = new HashMap<>();
            try (PreparedStatement statement = owner.prepareStatement("SELECT BankId, count(*) "
                    + "FROM AuditEvent WHERE Action = 'VERSION_DEPLOYED' AND EntityId = ? GROUP BY BankId")) {
                statement.setString(1, version);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        perBank.put(rs.getInt(1), rs.getInt(2));
                    }
                }
            }
            assertThat(perBank).containsKeys(BANK_A, BANK_B, platformBankId);
            try (Statement statement = owner.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM Bank")) {
                rs.next();
                assertThat(perBank).as("one row in EVERY bank").hasSize(rs.getInt(1));
            }
            assertThat(perBank.values()).allMatch(count -> count == 1);
            assertThat(single(owner, "SELECT count(*) FROM Announcement WHERE Kind = 'RELEASE' "
                    + "AND ReleaseVersion = ? AND BankId = " + platformBankId, version)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The committed platform bank the application resolves (code MOI-PLATFORM). */
    private static int platformBank(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (Code, Name, Kind) VALUES ('" + PLATFORM_CODE
                    + "', 'MO Intelligence Platform', 'PLATFORM') ON CONFLICT DO NOTHING");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT BankId, Code FROM Bank WHERE Kind = 'PLATFORM'")) {
                assertThat(rs.next()).as("a platform bank exists").isTrue();
                assertThat(rs.getString(2))
                        .as("the committed platform bank must carry the default platform code")
                        .isEqualTo(PLATFORM_CODE);
                return rs.getInt(1);
            }
        }
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            statement.executeQuery().close();
        }
    }

    private static int countOurs(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM Announcement WHERE Title LIKE '" + TAG + " %' "
                             + "AND Title <> '" + TAG + " via API'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int single(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String bearer(int userId, int bankId, Role role) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    private JsonNode active(int userId, int bankId, Role role) throws Exception {
        String json = mvc.perform(get("/api/announcements/active")
                        .header("Authorization", bearer(userId, bankId, role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json).get("announcements");
    }

    /** Our seeded banners by title (the recorder may have added a RELEASE one). */
    private static Map<String, JsonNode> byTitle(JsonNode list) {
        Map<String, JsonNode> titles = new HashMap<>();
        for (JsonNode node : list) {
            if (node.get("title").asText().startsWith(TAG)) {
                titles.put(node.get("title").asText(), node);
            }
        }
        return titles;
    }
}
