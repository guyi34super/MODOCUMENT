# On-prem Connector Agent

Many banks cannot open their databases, internal APIs or SFTP servers to a
cloud service. The **Connector Agent** solves this. The bank runs it inside
its own network, and it connects those systems to MoDocument without any
inbound path: the agent only ever calls **out**.

- Install, configuration reference and allow-list: [`agent/README.md`](../../agent/README.md).
- Protocol shapes: [`docs/phase2/contracts.md`](../phase2/contracts.md) §7.4.

## Architecture

```
 bank network                                   │  MoDocument (AWS)
                                                │
 ┌───────────────┐  JDBC / HTTPS / SFTP         │
 │ core DB, CRM, │◀──────────┐                  │
 │ SFTP server   │           │                  │
 └───────────────┘   ┌───────┴────────┐  HTTPS  │  ┌──────────┐   ┌────────────────────┐
                     │ Connector Agent │────────────▶│ ALB/nginx │──▶│ /api/agent/v1/**    │
  allowlist.yml ───▶ │  (no inbound    │ outbound│  └──────────┘   │ AgentRunPort (flow  │
  local secrets ───▶ │   port)         │  only   │                 │ engine, SE4)        │
                     └────────────────┘          │                 └────────────────────┘
```

An **AGENT-mode connection** names the agent that serves it (`AgentId`)
and a `secretRef`, the name of a credential that exists only on the
agent's host. When a flow runs, the flow engine queues the run as
`AWAITING_AGENT`. The agent picks it up:

| Step | Agent → MoDocument | Notes |
|---|---|---|
| Long-poll | `GET /api/agent/v1/tasks?wait=25` | Server cap 30 s. No request thread waits: a `DeferredResult` is answered by a scheduler that re-tries the claim every 2 s. |
| Inbound (READ) | `POST /runs/{id}/records` (pages ≤ 1000), then `POST /runs/{id}/result` | Records feed the normal ingestion pipeline. |
| Outbound (WRITE) | `GET /runs/{id}/payload`, write locally, `POST /runs/{id}/result` | The payload is already masked and capped by MoDocument. A successful result is logged as `EXPORT_RUN` on the flow, like a DIRECT delivery (`mode: AGENT`, the agent id, the format and the row count the agent reports). |
| Connection test | `POST /runs/{id}/result` | `operation = TEST` |
| Liveness | `POST /heartbeat` every 30 s | Version and per-connection health. The answer carries `latestVersion`. |

### SFTP file records

An SFTP READ run sends **one record per file** in
`POST /runs/{id}/records`. The shape is fixed by
`application/port/integration/AgentSftpFile` (constants `FILE_NAME`,
`REMOTE_PATH`, `SIZE`, `CONTENT_BASE64`); `AgentRunService` parses every
record with `AgentSftpFile.fromRecord`, and the agent's `SftpConnector`
writes the same four keys:

| Key | Type | Required | Meaning |
|---|---|---|---|
| `fileName` | string | yes | The file's name: one path segment (`/` and `\` are refused). |
| `remotePath` | string | no | Where the file was on the bank's server; logged on the run. Defaults to `fileName`. |
| `size` | number | no | Byte count before encoding. Informational: the server measures the decoded bytes. |
| `contentBase64` | string | yes | The file, standard base64. |

```json
{"records": [{"fileName": "stmt-0131.csv", "remotePath": "stmt-0131.csv",
              "size": 2048, "contentBase64": "RGF0ZSxEZXNjcmlwdGlvbi..."}],
 "nextCursor": null, "last": true}
```

Each file is decoded, checked against
`modocument.integration.connectors.max-file-bytes` (a 400 above it)
and sent through the normal file pipeline at once, exactly like a DIRECT
SFTP pull. The run counts files in `recordsIn`. Changing these keys is a
protocol change for both builds (see
[`connector-sharing.md`](connector-sharing.md)).

The dashboard shows each agent as `PENDING_ENROLMENT`, `ONLINE`, `OFFLINE`
or `REVOKED`. `OFFLINE` means the last heartbeat is older than the bank
setting `integration.agentHeartbeatTimeout` (default 2 min). The dashboard
also shows the agent's version and an **Update available** notice when
the agent reports an older version than the running MoDocument. Agents are
released with the same version number.

## Enrolment

```
admin A ── POST /api/admin/integrations/agents {name} ──▶ 202 PendingChange (AGENT_REGISTER)
admin B ── approve ──▶ agent row PENDING_ENROLMENT + one-time token (15 min), audit AGENT_REGISTERED
admin A ── GET …/agents/{id}/enrolment ──▶ {token, expiresAt, dockerCommand}    (maker only, once)
agent   ── POST /api/agent/v1/enroll {token, name, version} ──▶ {agentId, clientId, secret, tokenUrl}
                                                               (ApiClient Kind AGENT, scope AGENT_CONNECT,
                                                                audit AGENT_ENROLLED)
agent   ── POST tokenUrl (client credentials) ──▶ short-lived bearer token ──▶ /api/agent/v1/**
```

- **The token** is `mda1.<agentId>.<43 chars>`: 256 random bits.
  MoDocument stores only the SHA-256 of the secret part. Enrolment
  compares the hashes in constant time, checks the expiry and that the
  agent is still `PENDING_ENROLMENT`, then clears the hash in the same
  transaction. The token works once.
- **The maker's single read.** The plaintext token is sealed for the maker
  with the connector `SecretVault` (AES-256-GCM, `MODOCUMENT_CONNECTOR_KEY`,
  per-bank key). Only the maker can read it, through
  `GET …/agents/{id}/enrolment`. The sealed copy is deleted by that read.
  A second read, a read after expiry, or a read after enrolment answers
  **410 Gone**: revoke the agent and register it again. This option was
  chosen over a plaintext "readable once" column, so the token is never at
  rest unencrypted.
- **The enrolment route is public** (`POST /api/agent/v1/enroll`): the token
  in the body is the credential. It is rate-limited per client IP (10 per
  15 min). Every refusal gives the same `401 AGENT_ENROLMENT_INVALID`.
  Before the token is resolved, the bank is unknown. So the agent row is
  read once by id through a registered `TenantContext.runAsSystem` call
  site (`AgentEnrolmentService#enroll`), which returns only the bank id.
  Everything else runs as that bank, under row level security.
- **Revocation** (`POST …/agents/{id}/revoke`, direct, audit
  `AGENT_REVOKED`) is terminal. Every later `/api/agent/v1` call of that
  agent is refused, because each call re-checks the agent's status.

## Security model

| Threat | Control |
|---|---|
| The bank's network is exposed to the internet | The agent has **no inbound port** (`web-application-type: none`, no `EXPOSE`). It only dials out over HTTPS. |
| Bank credentials leak from the cloud | Credentials **never leave the bank's host**. MoDocument stores only the `secretRef` name. The agent reads the value from its environment or a mounted secrets folder, never logs it, and masks it in any error it reports. |
| A compromised MoDocument account (or insider) makes the agent read arbitrary data | The **bank-controlled allow-list** (`allowlist.yml`, mounted read-only) names the exact hosts and ports, tables or views, SFTP folders and REST path prefixes the agent may touch. It is checked **before** a connection is opened or a secret is resolved. A missing file refuses everything. Database access is by identifier only: there is never free SQL, and reads are `setReadOnly(true)`. |
| Stolen agent credentials act for another agent or bank | Every `/api/agent/v1` call needs `AGENT_CONNECT`, which only API clients of Kind AGENT get. The caller must also be the ApiClient issued to that agent, in the agent's bank, and the agent must be enrolled and not revoked. Runs of other agents are "not found". |
| Token replay or brute force | Single use, 15 minutes, 256 bits, constant-time compare, per-IP rate limit. |
| SFTP man-in-the-middle | The host key is **pinned** (`SHA256:` fingerprint in the connection). Unknown keys are refused, with no trust on first use. |
| Redirect or SSRF from REST targets | Redirects are never followed. Paths are relative to the configured base URL and cannot leave its host. Responses are capped at 20 MB. |
| Long polls exhaust the server | `wait` is capped at 30 s. Waiting polls hold no servlet thread, there is one waiter per agent, and at most 500 waiters per instance. Beyond that, a poll answers empty at once. |
| A tampered or outdated agent image | The image is distroless, non-root (uid 65532) and works with a read-only root file system. It is Trivy-scanned (fixable HIGH/CRITICAL fail the release) and released with a CycloneDX SBOM. The bank upgrades by pulling a new image; the agent never updates itself. |

The agent client's own credentials (`credentials.json`) live in the
state volume with mode `0600` and the folder `0700`. If they are lost,
revoke the agent and register a new one.

## Allow-list

See [`agent/README.md`](../../agent/README.md#allow-list) for the format.
Design rules:

- **Fail closed.** A missing or invalid file means every task is refused.
  The reason goes into the run's error ("Refused by the agent's local
  allow-list: …"), so the integration admin sees why.
- **Exact matches.** Destinations are compared by `host` + `port`, and
  tables by their full `[schema.]table` name, case-insensitively. A table
  without a schema is qualified with the connection's `schema`. SFTP
  folders cover their sub-folders, after normalisation (`..` is refused
  outright).
- **The bank owns the file.** Mount it read-only from a path that only the
  bank's administrators can change. Changes are picked up when the file's
  modification time changes.

## Delivery and upgrade

`release.yml` builds the agent image once per release tag, scans it and
writes its SBOM. The GitHub Release then publishes the image unchanged as
`ghcr.io/<owner>/modocument-agent:<version>` and attaches
`modocument-agent-<version>.jar` and the SBOM. CI runs the agent's tests
(`mvn -B -f agent/pom.xml verify`) on every push. `agent/pom.xml` carries
the release version (`scripts/check-version-sync.sh`).

## Limits of this version (later work)

- The agent has its own minimal connectors (DATABASE, REST_API, SFTP). One
  shared implementation with the server's `IntegrationConnector` beans is a
  later refactor, planned in [`connector-sharing.md`](connector-sharing.md).
- REST reads support pagination `NONE` only. Agent authentication is OAuth2
  client credentials. mTLS for the agent itself is left for later; the
  server side, the ALB listener and the nginx pass-through exist already.
- SFTP reads upload each file as one record
  `{fileName, remotePath, size, contentBase64}` (see
  [SFTP file records](#sftp-file-records)). The flow engine turns these
  into file ingestions.
- Per-bank site-to-site VPN or PrivateLink for DIRECT mode is
  "implement later" per bank (root README).
