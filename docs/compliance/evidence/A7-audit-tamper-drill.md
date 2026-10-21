# A7 — Audit tamper drill (SEC-035), 2026-10-19

**Purpose.** The workbook acceptance for SEC-035 says: manually editing an audit row in a test database is (a) blocked by grants and (b), if forced as superuser, flagged by the next verification run. This drill checks both on a database migrated with the real Flyway chain.

**Where.** A fresh database created and migrated by `AuditAppendOnlyPgIT` (run R3 in [A7-runs.md](A7-runs.md)) on the local PostgreSQL 16.14 test server. Synthetic rows only (the IT's `PG_IT_PROBE` row). Tester: Agent A7 (automated re-performance); human sign-off required.

**Command.** `psql -h 127.0.0.1 -p 55432 -U postgres -d <the R3 database> -f audit_tamper_demo.sql`

## Script

```sql
-- 1. Privileges of the runtime role on AuditEvent (V14 REVOKE)
SELECT grantee, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges
FROM information_schema.role_table_grants
WHERE table_name = 'auditevent' AND grantee = 'modocument_app' GROUP BY grantee;
-- 2. Append-only triggers
SELECT tgname, tgenabled FROM pg_trigger
WHERE tgrelid = 'auditevent'::regclass AND NOT tgisinternal ORDER BY tgname;
-- 3. Columns (is there a hash chain?)
SELECT string_agg(column_name, ', ' ORDER BY ordinal_position)
FROM information_schema.columns WHERE table_name = 'auditevent';
-- 4. Superuser bypass inside a transaction, then rolled back
BEGIN;
SELECT AuditEventId, Action FROM AuditEvent WHERE Action = 'PG_IT_PROBE' ORDER BY OccurredAt LIMIT 1 \gset probe_
SET LOCAL session_replication_role = replica;
UPDATE AuditEvent SET Action = 'TAMPERED' WHERE AuditEventId = :'probe_auditeventid';
SELECT 'after forced update' AS step, Action FROM AuditEvent WHERE AuditEventId = :'probe_auditeventid';
ROLLBACK;
SELECT 'after rollback' AS step, Action FROM AuditEvent WHERE AuditEventId = :'probe_auditeventid';
```

## Output

```
== 1. Privileges on AuditEvent (V14 REVOKE): runtime role modocument_app
    grantee     |  privileges
----------------+---------------
 modocument_app | INSERT,SELECT

== 2. Append-only triggers on AuditEvent
           tgname           | tgenabled
----------------------------+-----------
 trg_auditevent_append_only | O
 trg_auditevent_no_truncate | O

== 3. Does any column hold a hash chain? (columns of AuditEvent)
 auditeventid, occurredat, bankid, actoruserid, actoremail, actorrole, sessionjti, requestid,
 ip, action, entitytype, entityid, oldvalues, newvalues, outcome

== 4. Superuser bypass: session_replication_role = replica skips ordinary triggers
BEGIN
SET
UPDATE 1
        step         |  action
---------------------+----------
 after forced update | TAMPERED
ROLLBACK
      step      |   action
----------------+-------------
 after rollback | PG_IT_PROBE
```

## Result

| Acceptance part | Result | Proof |
|---|---|---|
| (a) The application role cannot edit or delete audit rows | **Pass.** `modocument_app` has INSERT and SELECT only. `AuditAppendOnlyPgIT` also shows UPDATE, DELETE and TRUNCATE fail with "permission denied", and the owner is stopped by the trigger. | `src/main/resources/db/migration/V14__audit_event.sql`, `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java` |
| (b) A forced superuser edit is flagged by the next verification run | **Fail.** A superuser can skip the triggers (`session_replication_role = replica`, or `ALTER TABLE … DISABLE TRIGGER`). The table has no hash or chain column, and no job verifies the trail, so nothing would detect the edit. | Columns above; no chain code exists under `src/main/java` (searched for hash-chain / previous-hash terms) |

The drill changed nothing: the update ran inside a transaction that was rolled back, and the row reads `PG_IT_PROBE` again afterwards.
