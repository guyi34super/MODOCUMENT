package com.modocument.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.SettingsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared H2 fixture of the ws20 settings suites: a customer bank with two
 * admins, an approver and a writer; a second customer bank with an admin;
 * and the PLATFORM bank (code {@code MOI-PLATFORM}) with two platform
 * admins. The platform bank (Kind = PLATFORM where that column exists) is
 * created once with a fixed id and never deleted — the platform bank
 * directory may cache its id. Platform admins are stored as ADMIN (the H2
 * role CHECK predates ws21) and carry the PLATFORM_ADMIN role in their
 * token, which is what every permission decision reads.
 *
 * <p>Every test's settings rows and pending changes are deleted afterwards
 * and the snapshot reloaded, so suites never see each other's values.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class SettingsIntegrationSupport {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final int PLATFORM_BANK_ID = 900_001;

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected AppUserJpaRepository users;
    @Autowired
    protected BankJpaRepository banks;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected SettingsCache settingsCache;

    protected Bank bank;
    protected Bank otherBank;
    protected Bank platformBank;
    protected AppUser admin;
    protected AppUser secondAdmin;
    protected AppUser approver;
    protected AppUser writer;
    protected AppUser foreignAdmin;
    protected AppUser platformAdmin;
    protected AppUser secondPlatformAdmin;
    private final List<Integer> createdUserIds = new ArrayList<>();

    @BeforeEach
    void seedBanksAndUsers() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                .toUpperCase();
        platformBank = ensurePlatformBank();
        bank = banks.save(new Bank("ST" + tag, "Settings Bank " + tag));
        otherBank = banks.save(new Bank("SO" + tag, "Other Bank " + tag));
        admin = storeUser("st-admin-" + tag, bank.getBankId(), Role.ADMIN);
        secondAdmin = storeUser("st-admin2-" + tag, bank.getBankId(), Role.ADMIN);
        approver = storeUser("st-approver-" + tag, bank.getBankId(), Role.APPROVER);
        writer = storeUser("st-writer-" + tag, bank.getBankId(), Role.READ_WRITE);
        foreignAdmin = storeUser("st-foreign-" + tag, otherBank.getBankId(), Role.ADMIN);
        platformAdmin = storeUser("st-platform-" + tag, platformBank.getBankId(), Role.ADMIN);
        secondPlatformAdmin = storeUser("st-platform2-" + tag, platformBank.getBankId(),
                Role.ADMIN);
        settingsCache.reload();
    }

    @AfterEach
    void cleanUp() {
        for (int bankId : new int[] {bank.getBankId(), otherBank.getBankId(),
                platformBank.getBankId()}) {
            jdbc.update("DELETE FROM BankSetting WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM PendingChange WHERE BankId = ?", bankId);
        }
        for (Integer id : createdUserIds) {
            users.deleteById(id);
        }
        createdUserIds.clear();
        banks.delete(bank);
        banks.delete(otherBank);
        settingsCache.reload();
    }

    private Bank ensurePlatformBank() {
        return banks.findByCode(PLATFORM_CODE).orElseGet(() -> {
            // A fixed id: survives an H2 schema recreated by another context,
            // so a cached directory id stays right. From ws21 on the lookup
            // also requires Bank.Kind = PLATFORM: set it whenever the column
            // exists.
            Integer kindColumns = jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'BANK' "
                    + "AND UPPER(COLUMN_NAME) = 'KIND'", Integer.class);
            if (kindColumns != null && kindColumns > 0) {
                jdbc.update("INSERT INTO Bank (BankId, Code, Name, CreatedDate, Kind) "
                        + "VALUES (?, ?, 'MO Intelligence', CURRENT_TIMESTAMP, 'PLATFORM')",
                        PLATFORM_BANK_ID, PLATFORM_CODE);
            } else {
                jdbc.update("INSERT INTO Bank (BankId, Code, Name, CreatedDate) "
                        + "VALUES (?, ?, 'MO Intelligence', CURRENT_TIMESTAMP)",
                        PLATFORM_BANK_ID, PLATFORM_CODE);
            }
            return banks.findByCode(PLATFORM_CODE).orElseThrow();
        });
    }

    protected AppUser storeUser(String emailPrefix, int bankId, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(emailPrefix.toLowerCase() + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(emailPrefix);
        entity.setBankId(bankId);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        createdUserIds.add(saved.getAppUserId());
        return saved;
    }

    protected String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    /** A token with the given role claim for a stored user (role checks read the token). */
    protected String bearer(AppUser user, Role role) {
        AppUser copy = new AppUser();
        copy.setAppUserId(user.getAppUserId());
        copy.setBankId(user.getBankId());
        copy.setRole(role);
        copy.setTokenVersion(user.getTokenVersion());
        return "Bearer " + jwtService.issueToken(copy);
    }

    protected String platformBearer(AppUser user) {
        return bearer(user, Role.PLATFORM_ADMIN);
    }

    protected ResultActions getBank(String token) throws Exception {
        return mvc.perform(get("/api/admin/settings").header("Authorization", token));
    }

    protected ResultActions putBank(String token, String body) throws Exception {
        return mvc.perform(put("/api/admin/settings").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions getPlatform(String token) throws Exception {
        return mvc.perform(get("/api/platform/settings").header("Authorization", token));
    }

    protected ResultActions putPlatform(String token, String body) throws Exception {
        return mvc.perform(put("/api/platform/settings").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions approve(String token, int pendingChangeId) throws Exception {
        return mvc.perform(post("/api/admin/changes/" + pendingChangeId + "/approve")
                .header("Authorization", token));
    }

    /** {@code {changes:[{key, value, expectedVersion}]}} with a raw JSON value. */
    protected static String change(String key, String jsonValue, int expectedVersion) {
        return "{\"changes\":[" + item(key, jsonValue, expectedVersion) + "]}";
    }

    protected static String item(String key, String jsonValue, int expectedVersion) {
        return "{\"key\":\"" + key + "\",\"value\":" + jsonValue + ",\"expectedVersion\":"
                + expectedVersion + "}";
    }

    protected static JsonNode body(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** One setting of a GET page by key. */
    protected static JsonNode setting(JsonNode page, String key) {
        for (JsonNode group : page.get("groups")) {
            for (JsonNode setting : group.get("settings")) {
                if (key.equals(setting.get("key").asText())) {
                    return setting;
                }
            }
        }
        throw new AssertionError("setting " + key + " not on the page");
    }

    protected String statusInDb(int pendingChangeId) {
        return jdbc.queryForObject("SELECT Status FROM PendingChange WHERE PendingChangeId = ?",
                String.class, pendingChangeId);
    }

    protected String storedValue(int bankId, String key) {
        List<String> values = jdbc.queryForList("SELECT ValueText FROM BankSetting "
                + "WHERE BankId = ? AND SettingKey = ?", String.class, bankId, key);
        return values.isEmpty() ? null : values.get(0);
    }

    protected int auditCount(String action, int bankId, String entityId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ? "
                + "AND BankId = ? AND EntityId = ?", Integer.class, action, bankId, entityId);
        return count == null ? 0 : count;
    }
}
