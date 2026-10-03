-- Flyway afterMigrate callback: re-applies the V13 tenant policy to EVERY
-- table that carries a BankId column (except AppUsers - see V13 for why the
-- auth tables are exempt). Runs after every successful migration, so a table
-- added by any later migration (UploadedFile in V20, and anything future
-- workstreams add) is RLS-covered the moment it exists - no one can forget.
-- RlsPolicyCoveragePgIT asserts the resulting invariant on a migrated
-- database. Idempotent: ENABLE is repeatable and the policy is only created
-- when absent. Keep the predicate in sync with V13__rls_policies.sql.

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
