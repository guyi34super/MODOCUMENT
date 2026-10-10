-- Row-level security: one tenant policy per BankId-stamped table, enforced
-- for the runtime role modocument_app (V12). This is the database-level
-- backstop under the application's OwnerUserId scoping: even a bypassed or
-- buggy application query can no longer read or write another bank's rows.
--
-- COVERAGE RULE: every table in this schema that carries a BankId column,
-- EXCEPT AppUsers — the authentication path (login, token refresh, the
-- per-request RevocationValidator) must read users BEFORE any tenant context
-- exists. As of this version that is:
--   bank, template, templatefields, validationrules, importhistory,
--   importdetails, ingestionjob, extractionsource, extractionjob,
--   extractionjobcounter, extractionrawrecord, extractionprocessedrecord,
--   extractionauditevent, extractionconnectorstate, docblueprint,
--   docblueprintsection, docinstance, docimageasset, doccontentblock.
-- UploadedFile only arrives in V20, so on a fresh database it does not exist
-- yet when this file runs; the afterMigrate__rls_enforce.sql callback applies
-- this same policy to EVERY BankId-carrying table after every migration run,
-- covering it and any future tenant table automatically.
--
-- THE TENANT comes from the app.tenant setting, bound twice by the app:
-- per connection CHECKOUT by TenantAwareDataSource (session scope, covers
-- non-transactional reads) and per transaction by
-- TenantAwareJpaTransactionManager (SET LOCAL via set_config(..., true)):
--   '<bankId>'   a request or job acting for one tenant (TenantContext);
--   '*'          TenantContext.runAsSystem maintenance work - all rows;
--   '' / unset   no tenant bound: the predicate is NULL, ZERO rows match.
--                FAIL CLOSED.
-- The CASE guard means a non-numeric setting is never cast to integer (the
-- '*' sentinel would otherwise abort the query instead of filtering it).
--
-- ENABLE, not FORCE: table owners (the Flyway migration user) and superusers
-- bypass these policies; modocument_app is subject to them.

DO $rls$
DECLARE
    tenant_table text;
    tenant_predicate constant text :=
        'current_setting(''app.tenant'', true) = ''*'''
        || ' OR BankId = CASE WHEN current_setting(''app.tenant'', true) ~ ''^[0-9]+$'''
        || ' THEN current_setting(''app.tenant'', true)::integer END';
BEGIN
    FOR tenant_table IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN pg_catalog.pg_tables t
          ON t.schemaname = c.table_schema AND t.tablename = c.table_name
        WHERE c.table_schema = current_schema()
          AND lower(c.column_name) = 'bankid'
          AND lower(c.table_name) <> 'appusers'
        ORDER BY c.table_name
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
        IF NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_policies p
            WHERE p.schemaname = current_schema()
              AND p.tablename = tenant_table
              AND p.policyname = tenant_table || '_tenant'
        ) THEN
            EXECUTE format('CREATE POLICY %I ON %I USING (%s) WITH CHECK (%s)',
                           tenant_table || '_tenant', tenant_table,
                           tenant_predicate, tenant_predicate);
        END IF;
    END LOOP;
END
$rls$;
