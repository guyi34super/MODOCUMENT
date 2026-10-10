package com.modocument.pgit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V210 Bank columns on the real migrated schema (ws21): exactly one
 * PLATFORM bank (unique partial index), a bank's kind never changes, the
 * Kind/Status vocabularies are closed, existing banks migrated to
 * CUSTOMER/ACTIVE, and the widened AppUsers role CHECK admits
 * PLATFORM_ADMIN but still not API_CLIENT. All as the schema owner in
 * rolled-back transactions.
 */
class PlatformBankSchemaPgIT extends PgItSupport {

    private static final int DEFAULT_BANK = 1; // seeded by V10

    @Test
    void exactlyOnePlatformBankCanExist() throws Exception {
        inRolledBackTx(owner -> {
            PlatformRoleTriggerPgIT.platformBank(owner);
            assertThatThrownBy(() -> insertBank(owner, "PLATFORM", "ACTIVE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ux_bank_single_platform");
            // Any number of customer banks, of course.
            insertBank(owner, "CUSTOMER", "ACTIVE");
            insertBank(owner, "CUSTOMER", "SUSPENDED");
        });
    }

    @Test
    void aBanksKindIsImmutable() throws Exception {
        inRolledBackTx(owner -> {
            int platform = PlatformRoleTriggerPgIT.platformBank(owner);
            assertThatThrownBy(() -> PlatformRoleTriggerPgIT.execute(owner,
                    "UPDATE Bank SET Kind = 'CUSTOMER' WHERE BankId = " + platform))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Bank.Kind is immutable");
            int customer = insertBank(owner, "CUSTOMER", "ACTIVE");
            assertThatThrownBy(() -> PlatformRoleTriggerPgIT.execute(owner,
                    "UPDATE Bank SET Kind = 'PLATFORM' WHERE BankId = " + customer))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Bank.Kind is immutable");
            // Status (suspension) and the rest stay writable.
            PlatformRoleTriggerPgIT.execute(owner, "UPDATE Bank SET Status = 'SUSPENDED', "
                    + "SuspendedAt = now(), Kind = Kind WHERE BankId = " + customer);
        });
    }

    @Test
    void kindAndStatusVocabulariesAreClosed() throws Exception {
        inRolledBackTx(owner -> {
            assertThatThrownBy(() -> insertBank(owner, "GALAXY", "ACTIVE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_bank_kind");
            assertThatThrownBy(() -> insertBank(owner, "CUSTOMER", "ZOMBIE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_bank_status");
        });
    }

    @Test
    void existingBanksMigratedToActiveCustomers() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT Kind, Status, SuspendedAt FROM Bank WHERE BankId = " + DEFAULT_BANK)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("CUSTOMER");
            assertThat(rs.getString(2)).isEqualTo("ACTIVE");
            assertThat(rs.getObject(3)).isNull();
        }
    }

    @Test
    void theRoleCheckAdmitsPlatformAdminButNeverApiClient() throws Exception {
        inRolledBackTx(owner -> {
            int platform = PlatformRoleTriggerPgIT.platformBank(owner);
            PlatformRoleTriggerPgIT.insertReturningId(owner, "INSERT INTO AppUsers (Email, "
                    + "PasswordHash, DisplayName, BankId, Role) VALUES ('pgit-" + UUID.randomUUID()
                    + "@example.com', 'x', 'pgit', " + platform + ", 'PLATFORM_ADMIN') "
                    + "RETURNING AppUserId");
            assertThatThrownBy(() -> PlatformRoleTriggerPgIT.execute(owner, "INSERT INTO AppUsers "
                    + "(Email, PasswordHash, DisplayName, BankId, Role) VALUES ('pgit-"
                    + UUID.randomUUID() + "@example.com', 'x', 'pgit', " + DEFAULT_BANK
                    + ", 'API_CLIENT')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_appusers_role");
        });
    }

    // ------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface TxBody {
        void run(Connection owner) throws Exception;
    }

    private static void inRolledBackTx(TxBody body) throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try {
                body.run(owner);
            } finally {
                owner.rollback();
            }
        }
    }

    private static int insertBank(Connection owner, String kind, String status)
            throws SQLException {
        return PlatformRoleTriggerPgIT.insertReturningId(owner,
                "INSERT INTO Bank (Code, Name, Kind, Status) VALUES ('PGIT-"
                        + UUID.randomUUID().toString().substring(0, 8) + "', 'pgit', '" + kind
                        + "', '" + status + "') RETURNING BankId");
    }
}
