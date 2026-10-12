package com.modocument.infrastructure.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws27 per-request auth reads: an authenticated request reads the caller's
 * AppUsers row ONCE. Before ws27 every request outside PROFILE_SELF read it
 * twice — the RevocationValidator's revocation probe, then the
 * PasswordChangeRequiredGate's MustChangePassword probe; now the gate
 * answers from the {@link RequestPrincipalState} the validator left in the
 * request.
 *
 * <p>Counted with H2's own statement statistics
 * ({@code INFORMATION_SCHEMA.QUERY_STATISTICS}) — the real security chain
 * and the real JDBC path, no extra test context. The endpoint
 * ({@code GET /api/ingestion/supported-types}, INGESTION_READ) touches no
 * table itself, so every AppUsers statement counted belongs to the auth
 * path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestPrincipalStateQueryCountTest {

    private static final String ENDPOINT = "/api/ingestion/supported-types";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;

    private Bank bank;
    private AppUser user;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bank = banks.save(new Bank("QC" + tag, "Query count bank"));
        AppUser entity = new AppUser();
        entity.setEmail("query-count-" + tag.toLowerCase() + "@example.com");
        entity.setPasswordHash("hash");
        entity.setDisplayName("Query count");
        entity.setBankId(bank.getBankId());
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        user = users.save(entity);
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("SET QUERY_STATISTICS FALSE");
        users.deleteById(user.getAppUserId());
        banks.delete(bank);
    }

    @Test
    void anAuthorizedRequestReadsAppUsersOnce() throws Exception {
        String token = jwtService.issueToken(user);

        long reads = appUsersReadsDuring(() -> mvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));

        // Before ws27: 2 (RevocationValidator + PasswordChangeRequiredGate).
        assertThat(reads).isEqualTo(1);
    }

    @Test
    void theForcedPasswordChangeGateStillBitesFromTheSameSingleRead() throws Exception {
        user.setMustChangePassword(true);
        user = users.save(user);
        String token = jwtService.issueToken(user);

        long reads = appUsersReadsDuring(() -> mvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()));
        assertThat(reads).isEqualTo(1);

        // No cache across requests: clearing the flag counts on the very next one.
        user.setMustChangePassword(false);
        user = users.save(user);
        mvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aRevokedTokenIsStillRejectedOnTheVeryNextRequest() throws Exception {
        String token = jwtService.issueToken(user);
        mvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        jdbc.update("UPDATE AppUsers SET TokenVersion = TokenVersion + 1 WHERE AppUserId = ?",
                user.getAppUserId());

        mvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** Executions of statements reading AppUsers while {@code request} runs. */
    private long appUsersReadsDuring(ThrowingRunnable request) throws Exception {
        jdbc.execute("SET QUERY_STATISTICS FALSE"); // clears the collected statistics
        jdbc.execute("SET QUERY_STATISTICS TRUE");
        try {
            request.run();
            Long count = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(EXECUTION_COUNT), 0) FROM INFORMATION_SCHEMA.QUERY_STATISTICS"
                            + " WHERE UPPER(SQL_STATEMENT) LIKE '%APPUSERS%'"
                            + " AND UPPER(SQL_STATEMENT) NOT LIKE '%QUERY_STATISTICS%'",
                    Long.class);
            return count == null ? 0 : count;
        } finally {
            jdbc.execute("SET QUERY_STATISTICS FALSE");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
