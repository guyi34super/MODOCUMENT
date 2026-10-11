package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.domain.model.settings.SettingsChangedEvent;
import com.modocument.infrastructure.persistence.BankSettingJpaRepository;
import com.modocument.infrastructure.persistence.ConfigRevisionJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.ConfigRevisionPoller;
import com.modocument.infrastructure.settings.SettingsCache;
import com.modocument.infrastructure.settings.SettingsCatalogue;
import com.modocument.infrastructure.settings.SettingsResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-task settings propagation on real PostgreSQL (ws20), with row level
 * security enforced: TWO {@link SettingsCache} instances on one database —
 * the application context's ("task A", which serves the HTTP writes) and a
 * second one built from the same beans ("task B"). A change written through
 * the real API on task A is visible on A right after commit and on B after
 * exactly one {@link ConfigRevisionPoller#poll()} — both for an operational
 * change (direct) and a security change (maker/checker approved by a second
 * admin). The writes run as the bank's tenant (RLS WITH CHECK), the reloads
 * as SYSTEM.
 *
 * <p>Wave 2: the change reaches a CONSUMER on task B too (a PasswordPolicy
 * reading task B's resolver), and task B announces each reload with a
 * {@link SettingsChangedEvent} — what the alert catalogue rebuilds on.
 */
class SettingsPropagationPgIT extends PgSpringItSupport {

    private static final int BANK = 2110;
    private static final int MAKER = 9110;
    private static final int CHECKER = 9111;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SettingsCache cacheA;
    @Autowired
    private SettingsResolver resolverA;
    @Autowired
    private SettingsCatalogue catalogue;
    @Autowired
    private BankSettingJpaRepository rows;
    @Autowired
    private ConfigRevisionJpaRepository revisions;
    @Autowired
    private PlatformBankLookup platformBanks;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void seedBankAndTwoAdminsAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(" + BANK + ", 'SETPROP', 'Settings Propagation Bank') "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(" + MAKER + ", 'setprop-maker@example.com', '{noop}x', 'Maker', '{}', "
                    + "now(), now(), " + BANK + ", 'ADMIN', 0), "
                    + "(" + CHECKER + ", 'setprop-checker@example.com', '{noop}x', 'Checker', "
                    + "'{}', now(), now(), " + BANK + ", 'ADMIN', 0) ON CONFLICT DO NOTHING");
            statement.execute("DELETE FROM BankSetting WHERE BankId = " + BANK);
        }
    }

    @Test
    void aChangeOnOneTaskIsVisibleOnAnotherAfterOnePoll() throws Exception {
        List<Object> eventsB = new ArrayList<>();
        SettingsCache cacheB = new SettingsCache(rows, revisions, catalogue, platformBanks,
                transactionManager, eventsB::add);
        cacheB.reload();
        SettingsResolver resolverB = new SettingsResolver(catalogue, cacheB);
        ConfigRevisionPoller pollerB = new ConfigRevisionPoller(cacheB);
        PasswordPolicy passwordPolicyB = new PasswordPolicy(12, resolverB);
        assertThat(resolverB.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK))
                .isEqualTo("AI_WITH_CODE_FALLBACK");

        // Operational: applied at once on task A (the writer).
        mvc.perform(put("/api/admin/settings").header("Authorization", token(MAKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[{\"key\":\"" + SettingKeys.EXTRACTION_DEFAULT_MODE
                                + "\",\"value\":\"CODE_ONLY\",\"expectedVersion\":0}]}"))
                .andExpect(status().isOk());
        assertThat(resolverA.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK))
                .isEqualTo("CODE_ONLY");
        // Task B still serves its old snapshot ...
        assertThat(resolverB.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK))
                .isEqualTo("AI_WITH_CODE_FALLBACK");
        // ... until its next poll.
        pollerB.poll();
        assertThat(resolverB.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK))
                .isEqualTo("CODE_ONLY");
        assertThat(cacheB.current().revision()).isEqualTo(cacheA.current().revision());
        assertThat(eventsB).last().isEqualTo(new SettingsChangedEvent(cacheB.current().revision()));
        int eventsAfterPoll = eventsB.size();
        assertThat(cacheB.refreshIfChanged()).as("nothing new: no reload").isFalse();
        assertThat(eventsB).as("no reload, no event").hasSize(eventsAfterPoll);

        // Security: maker/checker on PostgreSQL, then one poll on task B.
        String submitted = mvc.perform(put("/api/admin/settings")
                        .header("Authorization", token(MAKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[{\"key\":\"" + SettingKeys.PASSWORD_MIN_LENGTH
                                + "\",\"value\":18,\"expectedVersion\":0}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode pending = MAPPER.readTree(submitted).get("pendingChange");
        mvc.perform(post("/api/admin/changes/" + pending.get("pendingChangeId").asInt()
                        + "/approve").header("Authorization", token(CHECKER)))
                .andExpect(status().isOk());
        assertThat(resolverA.getInt(SettingKeys.PASSWORD_MIN_LENGTH, BANK)).isEqualTo(18);
        assertThat(resolverB.getInt(SettingKeys.PASSWORD_MIN_LENGTH, BANK)).isEqualTo(12);
        assertThat(passwordPolicyB.minLength(BANK)).isEqualTo(12);
        pollerB.poll();
        assertThat(resolverB.getInt(SettingKeys.PASSWORD_MIN_LENGTH, BANK)).isEqualTo(18);
        // The consumer on task B follows without a restart; other banks do not move.
        assertThat(passwordPolicyB.minLength(BANK)).isEqualTo(18);
        assertThat(passwordPolicyB.minLength(BANK + 1)).isEqualTo(12);
        assertThat(eventsB).last()
                .isEqualTo(new SettingsChangedEvent(cacheB.current().revision()));
    }

    private String token(int userId) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(BANK);
        user.setRole(Role.ADMIN);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }
}
