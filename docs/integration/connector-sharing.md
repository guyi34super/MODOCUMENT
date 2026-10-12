# Design note: one connector library for the server and the agent

Status: **built in 1.2.0** (optimization round, Wave 2), as option B below.
Owner: SE4 (connectors), with SE5 (agent, CI, release) and the Supervisor
(`pom.xml`, workflows).

## 1. Problem (before 1.2.0)

The bank integration hub had two copies of every connector.

| Concern | Server (`src/main/java/.../infrastructure/integration/connector/`) | Agent (`agent/src/main/java/.../agent/exec/`) |
|---|---|---|
| SPI | `application/port/integration/IntegrationConnector` (validate, test, read, write) | `Connector` (authorize, test, read, write) |
| DATABASE | `DatabaseIntegrationConnector` + `DbDialect` + `export/JdbcIdentifiers` (≈ 790 lines) | `DatabaseConnector` (≈ 510 lines) |
| REST_API | `RestApiIntegrationConnector` + `OutboundHttp` + `TlsMaterial` (≈ 820 lines) | `RestConnector` (≈ 500 lines) |
| SFTP | `SftpIntegrationConnector` (≈ 500 lines) | `SftpConnector` (≈ 330 lines) |
| WEBHOOK | `WebhookIntegrationConnector` + `WebhookSigner` | none (DIRECT only) |
| Wire shapes | `ConnectionContext`, `SecretMaterial`, `ReadBatch`, `OutboundBatch`, `WriteResult`, `AgentSftpFile` | `cloud/Protocol` records, hand-written SFTP record maps |
| Destination check | `EgressGuard` (bank egress rules, SE1) | `AllowList` (bank-owned `allowlist.yml`) |

The copies had drifted (section 6 lists every difference and how it was
resolved), and every security rule — identifiers only, paths confined, a
pinned SSH host key, timeouts on every call, secrets kept out of messages —
had to be kept, fixed and tested twice.

## 2. Goal and non-goals

**Goal:** one implementation of the DATABASE, REST_API, SFTP and WEBHOOK
connectors, used by the server for DIRECT connections and by the agent for
AGENT connections.

**Non-goals (kept):**

- Changing the agent protocol (`/api/agent/v1`, contracts §7.4). Agents of
  release N keep working with a server of release N+1; the SFTP record
  shape is unchanged.
- Spring in the connector code.
- Moving the application's `src/` (option C).
- Changing the integration API, the DB schema or the server's
  `IntegrationConnector` SPI.

## 3. Packaging options

| Option | Shape | Main cost | Verdict |
|---|---|---|---|
| A. Copy-and-sync | Keep two copies, add a parity test | Drift continues; every fix still lands twice | Rejected |
| **B. Sibling Maven project** | `connector-core/` with its own `pom.xml`, next to `agent/`. Both builds depend on `com.modocument:modocument-connector-core:${project.version}`. It is installed into the local repository first. | One extra build step per job; Docker builds need the core sources | **Built (1.2.0)** |
| C. Root reactor | Root `pom.xml` becomes an aggregator; the app moves to `app/`, with modules `connector-core`, `app`, `agent` | Moves every app path (Dockerfile, CI, semgrep and gitleaks paths, IDE settings, `docs/` links); large merge risk | Later, if ever |

A root aggregator *without* moving the app (the root pom listing
`connector-core`, itself and `agent` as modules) is not possible: the root
pom IS the app (`packaging jar`), and an aggregator must be `packaging pom`.
Option B keeps contracts §7.5 (`agent/` is a standalone Maven project, not a
reactor module) and can become option C later without code changes.

## 4. As built

```
connector-core/
  pom.xml          com.modocument:modocument-connector-core, the release version
                   (check-version-sync.sh / bump-version.sh). Plain Java 21, NO
                   Spring: jackson-databind, sshd-sftp (2.15.0, as both consumers),
                   slf4j-api. Versions come from the Spring Boot 3.5.3 BOM as a
                   version table only, so all three builds resolve the same ones.
                   JDBC drivers stay in the consuming builds (no driver licences).
  src/main/java/com/modocument/connector/core/
    spi/      Connector (validate, authorize, test, read, write), ConnectionContext,
              ConnectionType, Operation, SecretMaterial, DestinationGuard,
              ReadBatch (+ InboundFile, notes), ReadAcknowledgement,
              OutboundBatch (+ chunks, batchSize), WriteResult, ConnectionTestResult
    support/  ConnectorLimits, ConnectorErrors, Redaction, Specs, JsonFlattener
    db/       DatabaseConnector, DbDialect, DbIdentifiers, DbTarget
    rest/     RestApiConnector, OutboundHttp, OutboundAuthType, TlsMaterial
    sftp/     SftpConnector
    webhook/  WebhookConnector, WebhookSigner
    wire/     AgentSftpFile (the agent protocol's SFTP record, one class for both)
  src/test/java/...   the resolved-drift tests (section 6), on H2, an embedded
                      MINA SSHD server and a JDK HttpServer stub
```

**Server** (`infrastructure/integration/connector/`): the four
`@Component` beans keep the `IntegrationConnector` SPI and their
constructors, and are thin adapters: `CoreBridge` maps the server's records
to the library's and back, `OutboundHttp` holds the one library
`OutboundHttp` (the OAuth2 token cache) of the application,
`IntegrationConnectorProperties` builds the `ConnectorLimits`, and
`ConnectorErrors` adds the server's full PII scrub (`LogSafe`) to the
library's messages. `export/JdbcIdentifiers`, `export/DatabaseExportService`
and the extraction REST connector use the library's identifier rule,
dialects and `JsonFlattener`. The domain enums stay (JPA stores them); the
library has its own, mapped by name.

**Agent** (`agent/exec/`): `CoreConnector` is the one adapter
(task → `ConnectionContext`, results → what `TaskExecutor` reports);
`DatabaseConnector`, `RestConnector` and `SftpConnector` only pick the
library connector and the agent's page caps (500 database rows per
watermark page, 1000 REST records: one records POST). The agent's limits:
10 s connect, 120 s per call (the bank database's query timeout), 50 MB
files, 20 MB responses, plain `http://` allowed (the server's connection
validation decides what a flow may target).

### The seams

1. **`DestinationGuard`** (both `EgressGuard` and the agent's `AllowList`):

   ```java
   public interface DestinationGuard {
       void checkHost(String host, int port);              // right before every connection
       default void checkTable(String qualifiedName) { }   // [schema.]table, as the spec names it
       default void checkFolder(String folder) { }         // absolute, normalized SFTP folder
       default void checkPath(String path) { }             // absolute REST request path
   }
   ```

   The server's guard is `EgressGuard.checkDestination(bankId, host, port)`
   for the context's bank (host checks only). The agent's is its
   `AllowList` (all four). The connectors ask `checkHost` right before every
   connection (database, SFTP, every HTTP request including an OAuth2 token
   call). `Connector.authorize(context, spec, operation)` is the fail-closed
   pre-flight the agent runs BEFORE it resolves the secret: host, then the
   table / folder / path the operation touches, then the validation — so a
   refused resource is reported as refused even when its name is not valid.

2. **Two-phase reads.** `read(...)` returns the batch plus a
   `ReadAcknowledgement`. The caller acknowledges the files it has safely
   stored: the server's `FlowRunner` the files whose ingestion job exists,
   the agent the whole page once `POST /runs/{id}/records` answered. Only
   acknowledged files are moved to `processed/` or deleted; a failed move
   fails the run ("they will be read again"). On the server a file that
   cannot be ingested (an unsupported type, say) stays in the folder, no
   longer holds back the files after it, and fails the run with its reason
   (before 1.2.0 it was moved to `processed/` and the run stopped there).
   DATABASE and REST reads have nothing to acknowledge.

3. **Secrets** (`SecretMaterial`, a one-method interface). The server
   passes the vault material `IntegrationConnectors` opened; the agent its
   `LocalSecrets` secret (a single unnamed secret answers any key; `contains`
   tells real keys apart). `ConnectionContext.secretRequired`: true for the
   server's DIRECT connections (validation demands the keys the auth type
   needs, as before); false for AGENT connections on the server and in the
   agent, where a missing key fails at call time ("The connection secret
   has no 'token'.") and a database password stays optional.

4. **Tenant** (`ConnectionContext.tenant`): the partition key of the OAuth2
   token cache (the server passes the bank id, as its cache key did before).

## 5. Build wiring

| Where | What |
|---|---|
| Local | `mvn -B -f connector-core/pom.xml install` once (and after changing it), then `mvn -B verify`, `mvn -B -f agent/pom.xml verify` as before. `mvn -B -f connector-core/pom.xml verify` runs the library's tests. |
| `Dockerfile` (context `.`) | `COPY connector-core` and install it (cache mount) before `dependency:go-offline`; the package step installs it again (a no-op on a warm cache, correct if BuildKit dropped the mount). `.dockerignore` keeps `connector-core/target` out. |
| `agent/Dockerfile` (context `agent/`) | The core sources come in as the NAMED build context `connector-core`, which replaces a stage of that name: `docker buildx build --build-context connector-core=connector-core agent`. Without it the build stops at that stage with this hint. |
| `ci.yml` | New job `connector-core` (`verify`). `build-and-test`, `backend-postgres`, `storage-s3` and `agent` install it first (`-DskipTests`); the agent job's Maven cache keys on both poms. |
| `release.yml` | The agent image gets `--build-context connector-core=connector-core`; the agent jar step installs the core first. The API image needs nothing (its context holds the core). |
| `sbom.yml` | Installs the core first; the backend BOM lists `com.modocument:modocument-connector-core` with its dependencies. The release's Trivy image SBOMs list it too (the jar is in both fat jars). |
| Versions | `scripts/version-lib.sh` `SIBLING_POMS="agent/pom.xml connector-core/pom.xml"`: `check-version-sync.sh` checks both, `bump-version.sh` moves both. The consumers depend on `${project.version}`. |
| Others | `e2e/run-local.sh` installs it first; Dependabot watches `/connector-core` (and `/agent`). |

The library is **not published** to a registry: it is embedded in both fat
jars, and the agent jar on the GitHub Release stays a single file.

## 6. Resolved drift

Rule used: **validation follows the server.** Every flow spec — AGENT ones
included — is validated by the server's connector when it is saved and
before every run (`IntegrationFlowUseCase`), so the agent only ever received
specs that pass the server's rules; adopting them changes nothing for stored
flows. Runtime behaviour takes the safer or more complete side. Each row is
pinned by tests in `connector-core` (and the existing suites on both sides).

| # | Drift | Before (server / agent) | 1.2.0, both sides | Pinned by |
|---|---|---|---|---|
| 1 | REST pagination | server PAGE stopped at a page shorter than `min(maxRecords, pageSize or 100)` even when no size was configured; agent read on until an empty page | the agent's rule: a short page ends the run only when a size is configured (`sizeParam` or `pageSize`), otherwise only an empty page does | `RestApiConnectorTest.pageWithoutAConfiguredSize…`, `…WithAConfiguredSize…`; agent `RestConnectorPaginationTest` |
| 2 | REST next link | server: same origin; agent: same origin AND path | same origin AND the configured path, no fragment, ≤ 1000 characters; refused with a `SecurityException` (the agent threw `IllegalArgumentException`) | `RestApiConnectorTest.aNextLinkMustKeepTheConfiguredPath`, server `aNextLinkToAnotherHostIsRefused`, agent `aNextLinkToAnotherHostOrPathIsRefused` |
| 3 | REST cursor / paths | server: no cursor length cap, no `$.`; agent: 1000-character cap, `$.` accepted | both: cap 1000, `$.` accepted in `cursorPath` and `recordsPath` (also in the extraction connector's `JsonFlattener`) | `RestApiConnectorTest.anOverlongCursorEndsTheRun`, `pageWithoutAConfiguredSize…` (`$.data`) |
| 4 | REST records and auth | server: records flattened to text, all six auth types, raw file push; agent: nested JSON values, NONE/BASIC/BEARER/API key only, records only | the server's: flattened records, OAuth2 and MTLS now on the agent too, a file batch is pushed as the file | `RestApiConnectorTest.aFileBatchIsPushedAsTheRawFile`, server REST/MTLS suites |
| 5 | SFTP move/delete timing | server moved or deleted while reading; agent after MoDocument accepted the records | **two-phase** (section 4): only acknowledged files move; on the server per file whose ingestion job exists — a file that cannot be ingested stays and fails the run, the others go through | `SftpConnectorTest.aReadMovesOnlyTheAcknowledgedFiles`, server `SftpIntegrationConnectorTest`, agent `readsMatchingFilesAndMovesThemOnceAccepted` |
| 6 | Oversized SFTP file | server skipped it with a WARN; agent failed the run | skipped, left in place, reported in `ReadBatch.notes` (server: WARN log + run log; agent: WARN log) — one big file no longer blocks the folder | `SftpConnectorTest.anOversizedFileIsSkipped…`, server `readMatchesTheGlob…` |
| 7 | SFTP details | agent: absolute spec folders under the base, spaces in names, `.name.part` temp files overwritten on retry, full-path `remoteReference`, `remotePath` = file name, password-or-key by secret content, no passphrase; server: relative folders only, `.tmp-<uuid>`, an existing final name = already delivered, `remotePath` relative to the base | the server's rules; `remotePath` relative to the connection folder (`inbox/a.csv`); the auth pick: `sftpAuth` (or legacy `authType PRIVATE_KEY`), else a secret holding only a `privateKeyPem` uses it, else the password. The SSH client reads no `~/.ssh` config or default keys (the agent's hardening) | `SftpConnectorTest.withoutAnAuthSetting…`, `writesAreAtomicAndIdempotent`, agent `SftpConnectorTest` |
| 8 | DATABASE identifiers | server: quoted per vendor, `[A-Za-z_][A-Za-z0-9_]*`, schema from the connection; agent: unquoted, `$` allowed, `schema.table` in the spec | the server's: quoted (so **case-sensitive**: on Oracle name the view in upper case, as on DIRECT), schema from the connection's `schema`; the allow-list still sees `schema.table` | `DatabaseConnectorTest.authorizeAsksTheGuard…`, agent `DatabaseConnectorSqlTest`, server `identifierInjectionIsRefused…` |
| 9 | DATABASE paging | server: `setMaxRows(max)`, "more" when a page was full; agent: SQL `LIMIT`/`TOP`/`FETCH FIRST` + 1 | `SELECT` without a vendor limit clause, `setMaxRows(max + 1)`: the look-ahead row only says whether more exists (no empty extra page) | `DatabaseConnectorTest.aPageOfExactlyMaxRows…` |
| 10 | DATABASE cursor | server `N:/T:/Z:/D:/S:`, agent `n:/ts:/tz:/d:/s:` | written as `N:/T:/Z:/D:/S:`; the agent's old prefixes (and untyped values) still bind, so cursors stored by older agents keep working | `DatabaseConnectorTest.cursorsAreTyped…`, agent `AgentEndToEndTest` (`ts:` cursor) |
| 11 | DATABASE values | server: text, binary dropped; agent: native JSON types, binary as base64 | text (numbers as their plain string, timestamps ISO), binary columns not record data | `DatabaseConnectorTest.valuesArriveAsText…` |
| 12 | DATABASE writes | server: nested values `toString()`, 500-row transactions (dispatcher); agent: nested values as JSON, one transaction per payload | nested values as JSON text; the agent chunks writes like the server's dispatcher (`OutboundBatch.chunks`: `batchSize`, else 100 REST / 500 DATABASE rows, keys `<key>-<n>`) | `DatabaseConnectorTest.nestedRecordValues…`, `SupportTest.writeChunks…` |
| 13 | DATABASE connection | agent: `DriverManager` discovery, `sslmode` for PostgreSQL/MariaDB only, Oracle `@//host`; server: the vendor's driver class, allow-listed options with the `sslmode` translation, `@tcp://host` | the server's | agent `DatabaseConnectorSqlTest.jdbcUrls…`, server `urlsAreBuiltPerVendor…` |
| 14 | Timeouts and caps | agent: 10 s connect, 60 s REST, 120 s SQL, 20 s SFTP; server: `ConnectorLimits` (10 s / 30 s by default) | `ConnectorLimits` everywhere; the agent's are 10 s / 120 s, 50 MB files, 20 MB responses | `CoreConnector.LIMITS` |
| 15 | Error messages | agent: raw exception text, masked against local secret values | the library's `ConnectorErrors` / `Redaction` (credential shapes) on test results; the agent still masks its secret values, the server adds its PII scrub | `SupportTest.errorMessagesAreClassified…`, agent `SecretsNeverLoggedTest` |

## 7. Measured

Main code (tests and docs excluded), `git diff --numstat` against the
Wave 1 head:

| | Removed | Added | Net |
|---|---|---|---|
| Server (`src/main`) | 2,580 | 241 | −2,339 (the connector package: 2,691 → 516 lines) |
| Agent (`agent/src/main`) | 1,358 | 256 | −1,102 (connectors: 1,455 → 349 lines) |
| `connector-core/src/main` | — | 3,474 | +3,474 (≈ 890 of it moved unchanged: `DbDialect`, `Specs`, `TlsMaterial`, `WebhookSigner`, `ConnectorLimits`, `JsonFlattener`, `AgentSftpFile`) |
| **Total** | **3,938** | **3,971** | **+33** |

Tests: `connector-core` 25 new (748 lines); the server's connector suites
unchanged apart from imports and the acknowledgement step in
`SftpIntegrationConnectorTest`; the agent's 41 kept (three rewritten for
the resolved drift, section 6).

**Duplication left** (none of it connector logic):

- the record types at the edges: the server's SPI records and domain enums,
  mapped by `CoreBridge` (61 lines); the agent's `Protocol.OutboundBatch`
  wire record, mapped in `CoreConnector`;
- `OutboundDispatcher.chunks` (server record types, ≈ 20 lines) mirrors
  `OutboundBatch.chunks`; its `batchSize` already delegates;
- `DatabaseExportService.translateSslMode` (≈ 20 lines) mirrors the
  connector's (it overrides explicit options where the connector keeps
  them);
- `Redaction`'s three credential patterns repeat three of `PiiPatterns`'.

## 8. Next steps (optional)

- A contract test suite published as a test-jar, run by both consumers
  against their adapters.
- Move the server's SPI records to the library's `spi` types (a mechanical
  import change), then `CoreBridge` goes away.
- Option C (root reactor) if the extra install step ever hurts.
