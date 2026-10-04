package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate B4 (lockout), over real HTTP semantics: five wrong passwords only
 * count, the sixth locks; while locked even the CORRECT password answers the
 * same generic 401 (no enumeration, no oracle); once LockedUntil passes the
 * correct password works again and every counter resets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountLockoutIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret-lock!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUser user;
    /** Distinct per run, so the shared per-IP login throttle never trips. */
    private String clientIp;

    @BeforeEach
    void createUser() {
        AppUser entity = new AppUser();
        entity.setEmail("lock-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entity.setDisplayName("Lockout Test");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        user = users.save(entity);
        clientIp = "10.6." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        refreshTokens.findByUserId(user.getAppUserId()).forEach(refreshTokens::delete);
        users.deleteById(user.getAppUserId());
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(
                        new com.modocument.application.dto.LoginRequest(user.getEmail(), password))));
    }

    @Test
    void sixthWrongAttemptLocksCorrectPasswordStays401UntilExpiryThenResets() throws Exception {
        // Five wrong attempts: all the same generic 401, no lock yet.
        for (int attempt = 1; attempt <= 5; attempt++) {
            login("wrong-password-" + attempt)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid email or password."));
            assertThat(users.findById(user.getAppUserId()).orElseThrow().getLockedUntil())
                    .as("attempt %d must not lock", attempt).isNull();
        }

        // The sixth locks...
        login("wrong-password-6").andExpect(status().isUnauthorized());
        AppUser locked = users.findById(user.getAppUserId()).orElseThrow();
        assertThat(locked.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(locked.getLockoutCount()).isEqualTo(1);
        // ... with the security signal on the audit trail (DENIED row).
        Integer lockedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'ACCOUNT_LOCKED' "
                        + "AND ActorUserId = ?", Integer.class, user.getAppUserId());
        assertThat(lockedEvents).isEqualTo(1);

        // While locked, even the CORRECT password answers the SAME generic
        // 401 — the lock is checked before the password is verified.
        login(PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password."));

        // Lock expiry: wind LockedUntil into the past instead of sleeping.
        AppUser expire = users.findById(user.getAppUserId()).orElseThrow();
        expire.setLockedUntil(LocalDateTime.now().minusSeconds(1));
        users.save(expire);

        // The correct password works again and resets every counter.
        login(PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        AppUser reset = users.findById(user.getAppUserId()).orElseThrow();
        assertThat(reset.getFailedLoginCount()).isZero();
        assertThat(reset.getLockoutCount()).isZero();
        assertThat(reset.getLockedUntil()).isNull();
    }

    /** Escalation: the second lockout waits for a fresh run of six failures. */
    @Test
    void lockoutBackoffDoublesOnTheSecondLock() throws Exception {
        AppUser primed = users.findById(user.getAppUserId()).orElseThrow();
        primed.setLockoutCount(1); // one lockout already behind us
        users.save(primed);

        for (int attempt = 1; attempt <= 6; attempt++) {
            login("wrong-password").andExpect(status().isUnauthorized());
        }

        AppUser locked = users.findById(user.getAppUserId()).orElseThrow();
        assertThat(locked.getLockoutCount()).isEqualTo(2);
        // Second lock: 2 minutes, not 1 (a strict check with slack for runtime).
        assertThat(locked.getLockedUntil())
                .isAfter(LocalDateTime.now().plusSeconds(90))
                .isBefore(LocalDateTime.now().plusSeconds(121));
    }
}
