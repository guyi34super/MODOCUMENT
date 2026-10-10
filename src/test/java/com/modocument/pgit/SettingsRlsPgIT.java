package com.modocument.pgit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Row level security of the ws20 settings tables on real PostgreSQL, as the
 * runtime role {@code modocument_app}:
 * <ul>
 *   <li>BankSetting — bank A reads only its own rows and can neither change
 *       nor create bank B's (nor the platform bank's: the platform layer is
 *       just another bank's rows, so a bank admin's transaction cannot write
 *       platform defaults or bounds); SYSTEM ({@code '*'}, the settings
 *       cache's reload) reads every bank;</li>
 *   <li>ConfigRevision — tenant-less: the runtime role reads and bumps it
 *       under any tenant, can never delete it, and the single-row CHECK
 *       holds.</li>
 * </ul>
 */
class SettingsRlsPgIT extends PgItSupport {

    private static final int BANK_A = 2010;
    private static final int BANK_B = 2020;
    private static final int PLATFORM = 2030;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(" + BANK_A + ", 'SETRLS_A', 'Settings RLS A'), "
                    + "(" + BANK_B + ", 'SETRLS_B', 'Settings RLS B'), "
                    + "(" + PLATFORM + ", 'SETRLS_P', 'Settings RLS Platform') "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO BankSetting "
                    + "(BankId, SettingKey, ValueText, BankMinText, BankMaxText, Version, UpdatedAt) "
                    + "VALUES (" + BANK_A + ", 'ratelimit.uploads', '30', NULL, NULL, 1, now()), "
                    + "(" + BANK_B + ", 'ratelimit.uploads', '40', NULL, NULL, 1, now()), "
                    + "(" + PLATFORM + ", 'ratelimit.uploads', NULL, '5', '50', 1, now()) "
                    + "ON CONFLICT (BankId, SettingKey) DO NOTHING");
        }
    }

    @Test
    void aBankReadsOnlyItsOwnSettings() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(BANK_A));
            assertThat(banksVisible(app)).containsExactly(BANK_A);
            assertThat(count(app, "SELECT count(*) FROM BankSetting WHERE BankId = " + BANK_B))
                    .isZero();
            app.rollback();
        }
    }

    @Test
    void aBankCannotChangeOrCreateAnotherBanksOrThePlatformsSettings() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(BANK_A));
            try (Statement statement = app.createStatement()) {
                // USING: foreign rows are invisible to UPDATE/DELETE.
                for (int foreign : new int[] {BANK_B, PLATFORM}) {
                    assertThat(statement.executeUpdate("UPDATE BankSetting SET ValueText = '200', "
                            + "BankMaxText = '200' WHERE BankId = " + foreign)).isZero();
                    assertThat(statement.executeUpdate(
                            "DELETE FROM BankSetting WHERE BankId = " + foreign)).isZero();
                }
                // Its own row: allowed.
                assertThat(statement.executeUpdate("UPDATE BankSetting SET ValueText = '31' "
                        + "WHERE BankId = " + BANK_A)).isOne();
            }
            app.rollback();
        }
        for (int foreign : new int[] {BANK_B, PLATFORM}) {
            try (Connection app = appDataSource().getConnection()) {
                app.setAutoCommit(false);
                setTenant(app, String.valueOf(BANK_A));
                // WITH CHECK: a row stamped with another bank is rejected.
                assertThatThrownBy(() -> {
                    try (Statement statement = app.createStatement()) {
                        statement.executeUpdate("INSERT INTO BankSetting "
                                + "(BankId, SettingKey, ValueText, Version, UpdatedAt) VALUES ("
                                + foreign + ", 'security.password.minLength', '64', 1, now())");
                    }
                }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
                app.rollback();
            }
        }
        // Control: the owner sees every seeded row unchanged.
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(text(owner, "SELECT ValueText FROM BankSetting WHERE BankId = " + BANK_B
                    + " AND SettingKey = 'ratelimit.uploads'")).isEqualTo("40");
            assertThat(text(owner, "SELECT BankMaxText FROM BankSetting WHERE BankId = "
                    + PLATFORM + " AND SettingKey = 'ratelimit.uploads'")).isEqualTo("50");
            assertThat(count(owner, "SELECT count(*) FROM BankSetting WHERE "
                    + "SettingKey = 'security.password.minLength' AND BankId IN ("
                    + BANK_B + ", " + PLATFORM + ")")).isZero();
        }
    }

    @Test
    void withoutATenantNothingIsVisibleAndSystemSeesEveryBank() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            assertThat(count(app, "SELECT count(*) FROM BankSetting")).isZero();
            app.setAutoCommit(false);
            setTenant(app, "*");
            assertThat(banksVisible(app)).contains(BANK_A, BANK_B, PLATFORM);
            app.rollback();
        }
    }

    @Test
    void configRevisionIsTenantLessReadableBumpableAndUndeletable() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(BANK_A));
            long before = count(app, "SELECT Revision FROM ConfigRevision WHERE Id = 1");
            try (Statement statement = app.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE ConfigRevision "
                        + "SET Revision = Revision + 1, UpdatedAt = now() WHERE Id = 1")).isOne();
            }
            assertThat(count(app, "SELECT Revision FROM ConfigRevision WHERE Id = 1"))
                    .isEqualTo(before + 1);
            app.rollback();
        }
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> {
                try (Statement statement = app.createStatement()) {
                    statement.executeUpdate("DELETE FROM ConfigRevision");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            app.rollback();
        }
        try (Connection owner = ownerDataSource().getConnection()) {
            owner.setAutoCommit(false);
            assertThatThrownBy(() -> {
                try (Statement statement = owner.createStatement()) {
                    statement.executeUpdate("INSERT INTO ConfigRevision (Id, Revision, UpdatedAt) "
                            + "VALUES (2, 0, now())");
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_configrevision_single_row");
            owner.rollback();
            assertThat(count(owner, "SELECT count(*) FROM ConfigRevision")).isOne();
        }
    }

    // ------------------------------------------------------------- plumbing

    private static List<Integer> banksVisible(Connection connection) throws SQLException {
        List<Integer> banks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT DISTINCT BankId FROM BankSetting ORDER BY BankId")) {
            while (rs.next()) {
                banks.add(rs.getInt(1));
            }
        }
        return banks;
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            try (ResultSet ignored = statement.executeQuery()) {
                // set_config returns the value; nothing to assert.
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
