package com.modocument.pgit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V210 platform-role trigger on the real migrated schema (ws21):
 * PLATFORM_ADMIN only inside the PLATFORM bank, and nothing but
 * PLATFORM_ADMIN inside it — on INSERT and on every UPDATE that moves a
 * user's Role or BankId. Proven as the schema owner (no app code in the
 * path) and as the RLS-subject runtime role, where the SECURITY DEFINER
 * function must see the platform Bank row that row level security hides
 * from the caller.
 *
 * <p>Every test runs in its own rolled-back transaction (the platform bank
 * included), so the shared per-JVM database stays clean.
 */
class PlatformRoleTriggerPgIT extends PgItSupport {

    private static final int DEFAULT_BANK = 1; // seeded by V10, Kind CUSTOMER

    @Test
    void platformAdminIsRefusedOutsideThePlatformBank() throws Exception {
        inRolledBackTx(owner -> {
            platformBank(owner);
            assertThatThrownBy(() -> insertUser(owner, DEFAULT_BANK, "PLATFORM_ADMIN"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("PLATFORM_ADMIN is only allowed in the platform bank");
        });
    }

    @Test
    void bankRolesAreRefusedInsideThePlatformBank() throws Exception {
        inRolledBackTx(owner -> {
            int platform = platformBank(owner);
            for (String role : new String[] {"ADMIN", "APPROVER", "READ_WRITE", "READ_ONLY"}) {
                assertThatThrownBy(() -> insertUser(owner, platform, role))
                        .as(role)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Only PLATFORM_ADMIN may belong to the platform bank");
            }
            // The one admitted placement.
            assertThat(insertUser(owner, platform, "PLATFORM_ADMIN")).isPositive();
        });
    }

    @Test
    void updatesThatMoveRoleOrBankAreCheckedToo() throws Exception {
        inRolledBackTx(owner -> {
            int platform = platformBank(owner);
            int platformAdmin = insertUser(owner, platform, "PLATFORM_ADMIN");
            int bankAdmin = insertUser(owner, DEFAULT_BANK, "ADMIN");

            // A platform admin cannot be moved into a customer bank ...
            assertThatThrownBy(() -> execute(owner, "UPDATE AppUsers SET BankId = "
                    + DEFAULT_BANK + " WHERE AppUserId = " + platformAdmin))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("PLATFORM_ADMIN is only allowed in the platform bank");
            // ... nor demoted to a bank role in place ...
            assertThatThrownBy(() -> execute(owner, "UPDATE AppUsers SET Role = 'ADMIN' "
                    + "WHERE AppUserId = " + platformAdmin))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Only PLATFORM_ADMIN may belong to the platform bank");
            // ... and a bank admin can neither move into the platform bank
            assertThatThrownBy(() -> execute(owner, "UPDATE AppUsers SET BankId = " + platform
                    + " WHERE AppUserId = " + bankAdmin))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Only PLATFORM_ADMIN may belong to the platform bank");
            // ... nor be promoted to platform admin where it stands.
            assertThatThrownBy(() -> execute(owner, "UPDATE AppUsers SET Role = 'PLATFORM_ADMIN' "
                    + "WHERE AppUserId = " + bankAdmin))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("PLATFORM_ADMIN is only allowed in the platform bank");

            // Ordinary saves (Hibernate rewrites every column) still pass.
            execute(owner, "UPDATE AppUsers SET LastLoginAt = now(), Role = Role, "
                    + "BankId = BankId WHERE AppUserId IN (" + platformAdmin + ", " + bankAdmin + ")");
        });
    }

    @Test
    void theTriggerSeesThePlatformBankPastRowLevelSecurity() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            try {
                setTenant(app, "*");
                int platform = platformBank(app);
                // Now act as a customer-bank tenant: RLS hides the platform
                // Bank row from this session entirely ...
                setTenant(app, String.valueOf(DEFAULT_BANK));
                assertThat(count(app, "SELECT count(*) FROM Bank WHERE BankId = " + platform))
                        .isZero();
                // ... yet the SECURITY DEFINER trigger still knows its kind.
                assertThatThrownBy(() -> insertUser(app, platform, "ADMIN"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Only PLATFORM_ADMIN may belong to the platform bank");
                assertThat(insertUser(app, platform, "PLATFORM_ADMIN")).isPositive();
                assertThatThrownBy(() -> insertUser(app, DEFAULT_BANK, "PLATFORM_ADMIN"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("PLATFORM_ADMIN is only allowed in the platform bank");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    void theTriggerFunctionIsSecurityDefinerWithAPinnedSearchPath() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT prosecdef, proconfig::text, "
                     + "has_function_privilege('" + APP_ROLE + "', oid, 'EXECUTE') "
                     + "FROM pg_catalog.pg_proc WHERE proname = 'appusers_enforce_platform_role'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("SECURITY DEFINER").isTrue();
            assertThat(rs.getString(2)).contains("search_path=pg_catalog, pg_temp");
            assertThat(rs.getBoolean(3)).as("nobody needs EXECUTE on a trigger function")
                    .isFalse();
            assertThat(rs.next()).as("exactly one such function").isFalse();
        }
    }

    // ------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface TxBody {
        void run(Connection owner) throws Exception;
    }

    /** Owner connection, autocommit off, always rolled back. */
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

    /**
     * The platform bank inside the current transaction: the existing one if
     * another suite committed it, else a fresh one (rolled back with us).
     */
    static int platformBank(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT BankId FROM Bank WHERE Kind = 'PLATFORM'")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return insertReturningId(connection, "INSERT INTO Bank (Code, Name, Kind) VALUES ('PGIT-"
                + UUID.randomUUID().toString().substring(0, 8) + "', 'PgIT platform', "
                + "'PLATFORM') RETURNING BankId");
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            statement.executeQuery().close();
        }
    }

    private static int insertUser(Connection connection, int bankId, String role)
            throws SQLException {
        return insertReturningId(connection,
                "INSERT INTO AppUsers (Email, PasswordHash, DisplayName, BankId, Role) VALUES "
                        + "('pgit-" + UUID.randomUUID() + "@example.com', 'x', 'pgit', "
                        + bankId + ", '" + role + "') RETURNING AppUserId");
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Runs one statement under a savepoint so a refusal aborts only itself. */
    static void execute(Connection connection, String sql) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            connection.rollback(savepoint);
            throw e;
        }
    }

    static int insertReturningId(Connection connection, String sql) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            connection.releaseSavepoint(savepoint);
            return rs.getInt(1);
        } catch (SQLException e) {
            connection.rollback(savepoint);
            throw e;
        }
    }
}
