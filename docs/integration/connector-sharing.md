# Design note: one connector library for the server and the agent

Status: **proposal for a later release**. Nothing in this note is built yet.
Owner: SE4 (connectors), with SE5 (agent, CI, release) and the Supervisor
(`pom.xml`, workflows).

## 1. Problem

The bank integration hub has two copies of every connector.

| Concern | Server (`src/main/java/.../infrastructure/integration/connector/`) | Agent (`agent/src/main/java/.../agent/exec/`) |
|---|---|---|
| SPI | `application/port/integration/IntegrationConnector` (validate, test, read, write) | `Connector` (authorize, test, read, write) |
| DATABASE | `DatabaseIntegrationConnector` + `DbDialect` + `export/JdbcIdentifiers` (≈ 790 lines) | `DatabaseConnector` (≈ 510 lines) |
| REST_API | `RestApiIntegrationConnector` + `OutboundHttp` + `TlsMaterial` (≈ 820 lines) | `RestConnector` (≈ 270 lines) |
| SFTP | `SftpIntegrationConnector` (≈ 500 lines) | `SftpConnector` (≈ 330 lines) |
| WEBHOOK | `WebhookIntegrationConnector` + `WebhookSigner` | none (DIRECT only) |
| Wire shapes | `ConnectionContext`, `SecretMaterial`, `ReadBatch`, `OutboundBatch`, `WriteResult`, `AgentSftpFile` | `cloud/Protocol` records, hand-written SFTP record maps |
| Destination check | `EgressGuard` (bank egress rules, SE1) | `AllowList` (bank-owned `allowlist.yml`) |

The copies have already drifted:

- **REST pagination.** The server supports `NONE`, `PAGE`, `CURSOR` and
  `LINK`. The agent supports `NONE` only and refuses the rest.
- **SFTP read semantics.** The server moves or deletes each file while it
  reads it. The agent defers `MOVE`/`DELETE` until MoDocument has accepted
  the records. The agent's way is the safer one: a failed upload does not
  lose the file.
- **Oversized SFTP files.** The server skips them with a WARN. The agent
  fails the run.
- **SFTP record shape.** Wave 4 pinned it in `AgentSftpFile`, but the
  agent still writes the map by hand.

Every security rule must be kept twice: identifiers only (never free SQL),
paths confined to the configured folder, a pinned SSH host key, timeouts on
every call, and secrets kept out of messages. Each fix must land twice,
and each copy needs its own tests.

## 2. Goal and non-goals

**Goal:** one implementation of the DATABASE, REST_API, SFTP and WEBHOOK
connectors, used by the server for DIRECT connections and by the agent for
AGENT connections, with one contract test suite.

**Non-goals:**

- Changing the agent protocol (`/api/agent/v1`, contracts §7.4). Agents of
  release N keep working with a server of release N+1.
- Putting Spring into the agent's connector code.
- Moving the application's `src/` (see option C, section 3).

## 3. Packaging options

| Option | Shape | Main cost | Verdict |
|---|---|---|---|
| A. Copy-and-sync | Keep two copies, add a parity test | Drift continues; every fix still lands twice | Rejected |
| **B. Sibling Maven project** | `connector-core/` with its own `pom.xml`, next to `agent/`. Both builds depend on `com.modocument:modocument-connector-core:${version}`. CI installs it into the local repository first. | One extra build step per job; Docker builds need the core sources | **Recommended** |
| C. Root reactor | Root `pom.xml` becomes an aggregator; the app moves to `app/`, with modules `connector-core`, `app`, `agent` | Moves every app path (Dockerfile, CI, semgrep and gitleaks paths, IDE settings, `docs/` links); large merge risk | Later, if ever |

Option B fits the current rule (contracts §7.5: `agent/` is a standalone
Maven project, not a reactor module). It can become option C later without
code changes.

## 4. Module layout (`connector-core/`)

```
connector-core/
  pom.xml                     groupId com.modocument, artifactId modocument-connector-core,
                              version = the release version (check-version-sync.sh)
  src/main/java/com/modocument/connector/
    spi/        Connector (= today's IntegrationConnector), ConnectionContext, SecretMaterial,
                ReadBatch (+ InboundFile), OutboundBatch, WriteResult, ConnectionTestResult,
                DestinationGuard, ReadAcknowledgement
    model/      ConnectionType, ConnectionMode, DbVendor, DbWriteMode, OutboundAuthType,
                PayloadFormat, PayloadKind (moved from domain/model/integration)
    wire/       AgentSftpFile, the AgentTask / OutboundBatch JSON records of the agent protocol
    support/    ConnectorLimits, ConnectorErrors (+ the LogSafe scrubbing the messages need),
                Specs, JsonFlattener
    db/         DatabaseConnector, DbDialect, JdbcIdentifiers
    rest/       RestApiConnector, OutboundHttp, TlsMaterial
    sftp/       SftpConnector
    webhook/    WebhookConnector, WebhookSigner
  src/test/java/com/modocument/connector/
    contract/   ConnectorContractSuite: abstract JUnit 5 suites, published as a test-jar
```

Dependencies are compile scope unless noted:

- `jackson-databind`
- `sshd-sftp` (MINA SSHD, the version pinned today in both poms)
- `slf4j-api`
- `java.sql`, from the JDK

There is no Spring. JDBC drivers stay in the consuming builds (runtime
scope), so the library carries no driver licences.

### Two seams decide the design

1. **`DestinationGuard`** replaces both `EgressGuard` and the agent's
   `AllowList`:

   ```java
   public interface DestinationGuard {
       void checkHost(String host, int port);              // server: bank egress rules
       default void checkTable(String qualifiedName) { }   // agent: allow-list tables
       default void checkFolder(String folder) { }         // agent: allow-list SFTP folders
       default void checkPath(String baseUrl, String path) { }  // agent: allow-list REST prefixes
   }
   ```

   The server adapts `EgressGuard` (bank id taken from the
   `ConnectionContext`). The agent adapts `AllowList`. The connectors call
   it before opening a connection or resolving a secret, so the agent's
   fail-closed rule is kept.

2. **Two-phase reads.** `read(...)` returns the batch plus a
   `ReadAcknowledgement`. The caller runs it after the records are safely
   stored: the server after the ingestion job exists, the agent after
   `POST /runs/{id}/records` answered 204. This adopts the agent's safer
   SFTP behaviour on the server too. For DATABASE and REST reads the
   acknowledgement does nothing. An oversized file becomes one rule, set in
   `ConnectorLimits`: skip and report it in `ReadBatch.notes`.

## 5. What changes in each build

**Server (`pom.xml`, SE4 plus the Supervisor):**

- Add the dependency on `modocument-connector-core`.
- `infrastructure/integration/connector/*` shrinks to a `@Configuration`.
  It builds the four connectors from `IntegrationConnectorProperties`, an
  `EgressGuard` adapter and the `ObjectMapper`.
- `application/port/integration` keeps the server-only ports:
  `SecretVault`, `RecordsIngestion`, `IntegrationEventLog`, `AgentRunPort`
  and `CurrentClientProvider`. Their signatures switch to the library's
  `spi` types; this is a mechanical import change.
- The enums leave `domain/model/integration`. JPA keeps storing them by
  name, so there is no migration.

**Agent (`agent/pom.xml`, SE5):**

- `exec/DatabaseConnector`, `RestConnector` and `SftpConnector` are
  deleted.
- `TaskExecutor` maps `Protocol.AgentTask` to a `ConnectionContext`, with
  the secret from `LocalSecrets` turned into `SecretMaterial`. It wraps
  `AllowList` as a `DestinationGuard`.
- `Protocol` reuses `wire/` records where the JSON is identical.
- The agent gains REST pagination for free.

## 6. CI

`ci.yml` (SE5):

| Job | Today | After |
|---|---|---|
| `connector-core` (new) | none | `mvn -B -f connector-core/pom.xml install`: unit tests plus the contract suites against Testcontainers PostgreSQL and MariaDB, the MINA SSHD test server and a JDK HttpServer stub. Oracle and SQL Server stay in the existing vendor job. |
| `build-and-test` | `mvn -B verify` | first `mvn -B -q -f connector-core/pom.xml install -DskipTests`, then the same |
| `backend-postgres` | `mvn -B verify -Ppostgres-it` | same pre-step |
| `agent` | `mvn -B -f agent/pom.xml verify` | same pre-step; the agent runs `ConnectorContractSuite` from the test-jar against its adapters |

The pre-step takes about 20 s with a warm `~/.m2` cache. The `cache-dependency-path`
of each job adds `connector-core/pom.xml`. `scripts/check-version-sync.sh`
and `scripts/bump-version.sh` add `connector-core/pom.xml` to the files
they keep in step.

## 7. Release and Docker impact

- **Images.** The app Dockerfile copies `connector-core/` and installs it
  in the build stage before `mvn package`. The agent image's context is
  `agent/` today. It needs the core sources too, so `release.yml` passes a
  BuildKit named context:
  `docker buildx build --build-context core=connector-core agent`, and
  `agent/Dockerfile` adds `COPY --from=core . /core` plus an install step.
  The release pipeline still builds each image once.
- **Artifacts.** The library is **not published** to a registry. It is an
  internal module embedded in both fat jars. The agent jar attached to the
  GitHub Release stays a single file.
- **SBOM.** Both CycloneDX SBOMs list `modocument-connector-core` with the
  release version, and its transitive dependencies (they are the same as
  today's). Trivy policy is unchanged.
- **Compatibility.** The wire protocol does not change. Agents of the
  previous release keep working, and banks see the change only as REST
  pagination support after upgrading the agent. `agent/README.md` and
  this folder's `agent.md` lose their "own minimal connectors" caveat.
- **No database change.** Rollback is the normal image rollback.

## 8. Plan (one PR per step, each releasable on its own)

| Step | Content | Behaviour change | Gate |
|---|---|---|---|
| 0 (done, Wave 4) | Pin the agent SFTP record shape (`AgentSftpFile`). Agent deliveries are audited as `EXPORT_RUN` like DIRECT ones. | none | existing |
| 1 | Create `connector-core` with `spi/`, `model/`, `wire/` and `support/`. The server depends on it. Add the CI pre-steps and the version-sync entry. | none | full server suite |
| 2 | Move the four server connectors into the library behind `DestinationGuard`. Add the contract suites. Introduce two-phase reads on the server. | server SFTP moves files after ingestion | full suite plus vendor ITs plus the bank-simulator E2E (pull, process, push) |
| 3 | The agent switches to the library and its `exec/` copies are deleted. The agent runs the contract suite. | agent gains REST pagination | agent suite plus E2E through the agent |
| 4 (optional) | Root reactor (option C) | none | full CI |

Effort: about 2 days for step 1, 3–4 days for step 2 (mostly tests) and
2 days for step 3.

The main risk is in step 2. The server's SFTP behaviour changes (moving
files later), so QA re-tests the inbound SFTP flow against the bank
simulator with an ingestion failure in the middle of a run.
