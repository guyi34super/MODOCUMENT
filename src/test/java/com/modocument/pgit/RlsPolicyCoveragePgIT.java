package com.modocument.pgit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-enforcing RLS coverage: EVERY table carrying a BankId column — except
 * AppUsers, which the authentication path must read before any tenant
 * context exists — must have row level security enabled and exactly one
 * tenant policy with both USING and WITH CHECK. A future migration that adds
 * a tenant table is covered automatically by the afterMigrate callback; if
 * someone removes that callback or disables a policy, this test fails.
 */
class RlsPolicyCoveragePgIT extends PgItSupport {

    private static final String TENANT_TABLES_QUERY = """
            SELECT c.table_name
            FROM information_schema.columns c
            JOIN pg_catalog.pg_tables t
              ON t.schemaname = c.table_schema AND t.tablename = c.table_name
            WHERE c.table_schema = 'public'
              AND lower(c.column_name) = 'bankid'
              AND lower(c.table_name) <> 'appusers'
            ORDER BY c.table_name
            """;

    @Test
    void everyBankIdTableHasRowSecurityAndExactlyOneTenantPolicy() throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            List<String> tenantTables = tenantTables(owner);

            // The full current inventory: V10/V11 roots and children, plus
            // the Bank registry itself, the V14 append-only audit trail and
            // the V20 UploadedFile (which only the afterMigrate callback can
            // cover on a fresh database).
            assertThat(tenantTables).containsExactlyInAnyOrder(
                    "bank",
                    "auditevent",
                    "template", "templatefields", "validationrules",
                    "importhistory", "importdetails", "ingestionjob",
                    "extractionsource", "extractionjob", "extractionjobcounter",
                    "extractionrawrecord", "extractionprocessedrecord",
                    "extractionauditevent", "extractionconnectorstate",
                    "docblueprint", "docblueprintsection",
                    "docinstance", "docimageasset", "doccontentblock",
                    "uploadedfile",
                    // V80 (ws10): the maker/checker queue is bank-scoped.
                    "pendingchange",
                    // V160 (ws18): a bank's security alerts are its own.
                    "alertlog",
                    // V200 (ws20): a bank's settings overrides; the platform
                    // layer is the platform bank's rows (WITH CHECK keeps
                    // every other bank out of them).
                    "banksetting");

            for (String table : tenantTables) {
                assertThat(rowSecurityEnabled(owner, table))
                        .as("row level security must be ENABLED on %s", table)
                        .isTrue();
                assertPolicyShape(owner, table);
            }
        }
    }

    @Test
    void appUsersIsTheDocumentedExemption() throws Exception {
        // Login + the per-request RevocationValidator read AppUsers before a
        // tenant exists; it must stay out of RLS, deliberately and visibly.
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(rowSecurityEnabled(owner, "appusers")).isFalse();
        }
    }

    private static List<String> tenantTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(TENANT_TABLES_QUERY)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static boolean rowSecurityEnabled(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rowsecurity FROM pg_catalog.pg_tables "
                        + "WHERE schemaname = 'public' AND tablename = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("table %s must exist", table).isTrue();
                return rs.getBoolean(1);
            }
        }
    }

    private static void assertPolicyShape(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policyname, qual, with_check FROM pg_catalog.pg_policies "
                        + "WHERE schemaname = 'public' AND tablename = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next())
                        .as("%s must have a tenant policy", table)
                        .isTrue();
                assertThat(rs.getString("policyname")).isEqualTo(table + "_tenant");
                assertThat(rs.getString("qual"))
                        .as("%s policy must have USING", table)
                        .isNotBlank()
                        .contains("app.tenant");
                assertThat(rs.getString("with_check"))
                        .as("%s policy must have WITH CHECK", table)
                        .isNotBlank()
                        .contains("app.tenant");
                assertThat(rs.next())
                        .as("%s must have exactly ONE policy", table)
                        .isFalse();
            }
        }
    }
}
