package com.modocument.pgit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complement of RlsPolicyCoveragePgIT: EVERY table WITHOUT a BankId
 * column is outside row level security, so each one is listed here with the
 * reason it may be tenant-less. A migration that adds a table without
 * BankId fails this test until someone decides — visibly, in review —
 * that the table holds no bank data (or adds the column, which puts it
 * under RLS automatically).
 */
class NonTenantTableInventoryPgIT extends PgItSupport {

    private static final String NON_TENANT_TABLES_QUERY = """
            SELECT t.tablename
            FROM pg_catalog.pg_tables t
            WHERE t.schemaname = 'public'
              AND NOT EXISTS (
                  SELECT 1 FROM information_schema.columns c
                  WHERE c.table_schema = t.schemaname
                    AND c.table_name = t.tablename
                    AND lower(c.column_name) = 'bankid')
            ORDER BY t.tablename
            """;

    /**
     * Every deliberately tenant-less table (lowercase, as pg_tables names
     * them), each with the migration that adds it and why it holds no bank
     * data.
     */
    private static final Set<String> NON_TENANT_TABLES = Set.of(
            // Flyway's own bookkeeping (owner only).
            "flyway_schema_history",
            // V1/V2 shared lookup vocabularies — the same for every bank;
            // writes need LOOKUP_ADMIN (platform owner only, phase 2).
            "processstep", "templatedatatypes",
            // V15 auth plumbing, read BEFORE any tenant exists (refresh
            // rotation; the per-request revocation check) and keyed by
            // the user, whose AppUsers row carries the BankId.
            "refreshtoken", "revokedtoken",
            // V41 MFA enrolment + recovery codes: keyed by the user, read
            // during login before the tenant is known (same reasoning).
            "usermfa", "recoverycode",
            // V200 (ws20): the settings change counter — one global row,
            // no bank data; the runtime role cannot delete it.
            "configrevision",
            // V240 (ws24): one row per deployed release version (version,
            // commit, time, environment) — the installation's, no bank data.
            "deployedversion"
    );

    @Test
    void everyTableWithoutBankIdIsAnExplicitDecision() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery(NON_TENANT_TABLES_QUERY)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        Set<String> unexpected = new TreeSet<>(tables);
        unexpected.removeAll(NON_TENANT_TABLES);
        Set<String> gone = new TreeSet<>(NON_TENANT_TABLES);
        tables.forEach(gone::remove);
        assertThat(tables)
                .as("""
                        Tenant-less tables changed. NEW without BankId: %s; listed but no \
                        longer tenant-less (or dropped): %s.
                        For each NEW table, decide in review:
                          (a) it holds bank data -> add a BankId column in its migration (RLS then \
                        covers it automatically; RlsPolicyCoveragePgIT checks the policy), or
                          (b) it holds NO bank data -> add its lowercase name to \
                        NON_TENANT_TABLES in NonTenantTableInventoryPgIT with a comment naming \
                        the migration (Vnnn) and why it is tenant-less.
                        For each listed table that is gone (or gained BankId): remove its entry.""",
                        unexpected, gone)
                .containsExactlyInAnyOrderElementsOf(NON_TENANT_TABLES);
    }
}
