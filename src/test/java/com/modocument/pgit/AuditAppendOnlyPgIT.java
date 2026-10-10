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
 * THE C7 APPEND-ONLY GATE on the real migrated PostgreSQL schema: the
 * runtime role can INSERT audit rows (inside tenant semantics — app.tenant
 * bound) but UPDATE/DELETE/TRUNCATE are refused at the privilege layer
 * (V14 REVOKE), and even the schema OWNER is stopped by the V14 trigger —
 * the trail cannot be edited by anyone, including a compromised app or a
 * careless operator. Also asserts the afterMigrate RLS callback picked the
 * table up (it carries BankId), so audit reads/writes are tenant-scoped
 * like everything else.
 */
class AuditAppendOnlyPgIT extends PgItSupport {

    private static final int DEFAULT_BANK = 1; // seeded by V10

    @Test
    void appRoleInsertsInsideTenantContextAndIsReadBackBankScoped() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false); // set_config(..., true) is tx-scoped
            setTenant(app, String.valueOf(DEFAULT_BANK));
            insertAuditRow(app, id, DEFAULT_BANK);
            assertThat(count(app,
                    "SELECT count(*) FROM AuditEvent WHERE AuditEventId = '" + id + "'"))
                    .isOne();
            app.commit();
        }
        // Visible to the owner; invisible to another tenant (RLS USING).
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, "424242");
            assertThat(count(app,
                    "SELECT count(*) FROM AuditEvent WHERE AuditEventId = '" + id + "'"))
                    .as("another tenant must not see the audit row")
                    .isZero();
            app.rollback();
        }
    }

    @Test
    void appRoleWithoutTenantCannotInsertFailClosed() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            // No app.tenant bound: the WITH CHECK predicate is NULL — refused.
            assertThatThrownBy(() -> insertAuditRow(app, UUID.randomUUID(), DEFAULT_BANK))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            app.rollback();
        }
    }

    @Test
    void appRoleUpdateDeleteAndTruncateAreDeniedAtThePrivilegeLayer() throws Exception {
        UUID id = seedRowAsOwner();
        // Autocommit: each refused statement is its own transaction (a shared
        // one would abort after the first error and muddy the messages). The
        // privilege check fires before RLS even looks at app.tenant.
        try (Connection app = appDataSource().getConnection()) {
            assertSqlFails(app, "UPDATE AuditEvent SET Action = 'TAMPERED' "
                    + "WHERE AuditEventId = '" + id + "'", "permission denied");
            assertSqlFails(app, "DELETE FROM AuditEvent WHERE AuditEventId = '" + id + "'",
                    "permission denied");
            assertSqlFails(app, "TRUNCATE AuditEvent", "permission denied");
        }
    }

    @Test
    void evenTheOwnerIsStoppedByTheAppendOnlyTrigger() throws Exception {
        UUID id = seedRowAsOwner();
        try (Connection owner = ownerDataSource().getConnection()) {
            assertSqlFails(owner, "UPDATE AuditEvent SET Action = 'TAMPERED' "
                    + "WHERE AuditEventId = '" + id + "'", "append-only");
            assertSqlFails(owner, "DELETE FROM AuditEvent WHERE AuditEventId = '" + id + "'",
                    "append-only");
            assertSqlFails(owner, "TRUNCATE AuditEvent", "append-only");
            // The row survived every attempt, unmodified.
            try (Statement statement = owner.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT Action FROM AuditEvent WHERE AuditEventId = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("PG_IT_PROBE");
            }
        }
    }

    @Test
    void rlsCallbackCoveredTheAuditTable() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT count(*) FROM pg_catalog.pg_policies
                     WHERE schemaname = current_schema()
                       AND tablename = 'auditevent'
                       AND policyname = 'auditevent_tenant'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1))
                    .as("afterMigrate__rls_enforce.sql must cover AuditEvent")
                    .isOne();
        }
    }

    // ------------------------------------------------------------- plumbing

    private static UUID seedRowAsOwner() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = ownerDataSource().getConnection()) {
            insertAuditRow(owner, id, DEFAULT_BANK);
        }
        return id;
    }

    private static void insertAuditRow(Connection connection, UUID id, int bankId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, ActorUserId, "
                        + "ActorEmail, ActorRole, RequestId, Ip, Action, EntityType, EntityId, "
                        + "OldValues, NewValues, Outcome) VALUES (?, now(), ?, 1, "
                        + "'pgit@example.com', 'ADMIN', 'pgit-req', '127.0.0.1', 'PG_IT_PROBE', "
                        + "'Probe', '1', '{\"k\":\"old\"}'::jsonb, '{\"k\":\"new\"}'::jsonb, "
                        + "'SUCCESS')")) {
            statement.setObject(1, id);
            statement.setInt(2, bankId);
            statement.executeUpdate();
        }
    }

    private static void assertSqlFails(Connection connection, String sql, String messagePart) {
        assertThatThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining(messagePart);
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
}
