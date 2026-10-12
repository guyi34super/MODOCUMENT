package com.modocument.pgit;

import com.modocument.application.dto.ParseResult;
import com.modocument.application.port.TenantContext;
import com.modocument.infrastructure.ai.AiResultCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA, optimization round, test plan P7 "none across banks" (contracts §5,
 * V286) on the REAL schema with row level security ENFORCED: bank A's
 * cached AI result is a hit for bank A only. Bank B — even with the
 * byte-identical cache key (same document text, template version, mode,
 * model and prompt) and even when the lookup names bank A — gets a miss,
 * so it pays for its own AI call and never sees bank A's cell values; a
 * hit increments the row's HitCount (the metric path, never an audit row).
 */
class AiResultCacheRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 890;
    private static final int BANK_B = 891;
    /** A sha256-shaped key both banks would compute for the same document. */
    private static final String KEY = "a".repeat(64);
    private static final String RESULT = "{\"table\":{\"columns\":[\"Name\"],\"rows\":[{\"Name\":\"Ada\"}]},"
            + "\"mapping\":{\"Name\":\"FirstName\"},\"strategy\":\"LLM_SINGLE_PASS\"}";

    @Autowired
    private AiResultCache cache;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(890, 'AIC_A', 'AI cache A'), (891, 'AIC_B', 'AI cache B') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("DELETE FROM AiResultCache WHERE BankId IN (890, 891)");
            try (PreparedStatement insert = owner.prepareStatement("INSERT INTO AiResultCache "
                    + "(BankId, CacheKey, PromptVersion, Model, ResultJson, CreatedAt) "
                    + "VALUES (?, ?, 'v1', 'fake', ?, now())")) {
                insert.setInt(1, BANK_A);
                insert.setString(2, KEY);
                insert.setString(3, RESULT);
                insert.executeUpdate();
            }
        }
    }

    @Test
    void anotherBankNeverHitsABanksCachedResult() throws Exception {
        Optional<ParseResult> own = TenantContext.runAs(BANK_A, () -> cache.lookup(BANK_A, KEY));
        assertThat(own).as("bank A's own entry").isPresent();
        assertThat(own.get().table().getRows()).hasSize(1);
        assertThat(hits()).as("a hit is counted on the row").isEqualTo(1);

        assertThat(TenantContext.runAs(BANK_B, () -> cache.lookup(BANK_B, KEY)))
                .as("same key, other bank: miss").isEmpty();
        assertThat(TenantContext.runAs(BANK_B, () -> cache.lookup(BANK_A, KEY)))
                .as("RLS hides bank A's row even when bank B's session names bank A").isEmpty();
        assertThat(hits()).as("misses never touch bank A's row").isEqualTo(1);

        // Bank B stores its own result under the same key: one row per (bank, key).
        assertThat(TenantContext.runAs(BANK_B, () -> {
            cache.store(BANK_B, KEY, "v1", own.get());
            return cache.lookup(BANK_B, KEY);
        })).isPresent();
        assertThat(count("SELECT count(*) FROM AiResultCache WHERE CacheKey = '" + KEY + "'"))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM AuditEvent WHERE BankId IN (890, 891) "
                + "AND Action LIKE 'AI_RESULT_CACHE%'")).as("never audited").isZero();
    }

    private static long hits() throws Exception {
        return count("SELECT HitCount FROM AiResultCache WHERE BankId = 890 AND CacheKey = '" + KEY + "'");
    }

    /** Read as the owner (bypasses RLS): the truth on disk. */
    private static long count(String query) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
