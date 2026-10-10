# Bank Integration Hub — security test plan (Wave 3)

Owner: QA. Scope: the integration hub of phase 2 (plan §6, contracts §3,
§7.3, §7.4). This file lists the security cases that must be automated
before Wave 3 exits (test plan §4, "the security set passes"), with concrete
inputs, the expected result, the test level and **the engineer whose suite
owns the case**. QA's own E2E coverage of the same cases is in
`e2e/tests/phase2/integration-hub.spec.js` (fixme until the merge) against
the bank simulator (`e2e/bank-simulator/`).

Levels: UNIT (no Spring), H2-IT (Spring + MockMvc on H2), PG-IT (`-Ppostgres-it`,
RLS enforced as `modocument_app`), CONN-IT (`-Pconnectors-it`, Testcontainers /
embedded MINA / JDK HTTP stub), E2E (Playwright + bank simulator).

General expectations for every refusal below:
- the error body is the standard `{error, code, message}` shape, with no
  stack trace, host internals, SQL text or secret material;
- a refusal is **audited** (the case names the action) and never leaves a
  partial side effect (no row written, no connection opened, no file moved);
- cross-tenant refusals answer **404**, never 403 (contracts §3).

| # | Area | Owner |
|---|---|---|
| S1–S6 | SSRF and egress | SE1 (`OutboundHostPolicy`, `EgressGuard`, `BankEgressRule`) + SE4 (connectors call the guard) |
| S7–S10 | SQL identifiers and DB connectors | SE4 |
| S11–S15 | SFTP | SE4 |
| S16–S19 | Idempotency | SE2 (API v1) + SE4 (outbox) |
| S20–S24 | Webhook signatures | SE4 (signer) + QA (bank-side verifier) |
| S25–S31 | Client authentication, revocation, mTLS | SE2 |
| S32–S35 | Masking and data leaving the tenant | SE4 (payloads) + SE1 (setting) |
| S36–S38 | Maker/checker on outbound | SE4 (applier) + SE3 (UI) |
| S39–S42 | Agent | SE5 |
| S43–S46 | Token cross-use and scopes | SE2 |
| S47–S49 | Secrets at rest and in logs | SE1 |

## 1. SSRF and egress (DIRECT connections)

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S1 | Private IP without an approved egress rule | `REST_API` connection `baseUrl=http://10.20.30.40:8080`; then `POST …/connections/{id}/test` | Submit 400 `EGRESS_NOT_APPROVED` (preferred) or the test returns `{ok:false, message:"destination not approved"}`; **no TCP connection** is attempted (assert with a listener on the address in CONN-IT) | UNIT `OutboundHostPolicyBankAwareTest#privateWithoutRuleRefused`; H2-IT `IntegrationConnectionControllerTest`; E2E AC-INT-17 (SE1) |
| S2 | Cloud metadata address is never allowed | `http://169.254.169.254/latest/meta-data/`, `http://[fd00:ec2::254]/`, `http://metadata.google.internal/` — even with an APPROVED rule for `169.254.0.0/16` | 400 at submit; an egress rule for a link-local or metadata range cannot even be requested (400) | UNIT `OutboundHostPolicyBankAwareTest#metadataAlwaysRefused`; H2-IT `EgressRuleApplierTest#metadataRangeRefused` (SE1) |
| S3 | Approved rule allows exactly host/CIDR **and** port | Rule `10.20.30.40/32:5432` APPROVED; connect to `10.20.30.40:5433` and `10.20.30.41:5432` | Both refused; `10.20.30.40:5432` allowed | UNIT (SE1) |
| S4 | DNS rebinding | Host resolves to a public IP at submit, to `127.0.0.1` at connect | Refused at connect time (`EgressGuard.checkDestination` re-resolves right before connecting) | UNIT with a fake resolver; CONN-IT (SE1 + SE4) |
| S5 | Redirect into a private range | Public REST endpoint answers `302 Location: http://10.0.0.5/` on an authenticated call | Redirect not followed; run fails with a safe message; no request to 10.0.0.5 | CONN-IT `RestConnectorConnIT#redirectNotFollowed` (SE4) |
| S6 | Egress rule approval is platform maker/checker | Bank admin requests; the same platform admin approves its own `EGRESS_RULE_APPROVE`; a bank admin calls `/api/platform/egress-rules/{id}/approve` | Self-approval 409; bank admin 403; second platform admin → APPROVED, audited `EGRESS_RULE_APPROVED` in both banks | H2-IT `EgressRuleApplierTest` (SE1); E2E AC-INT-17b |

## 2. SQL identifiers and database connectors

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S7 | Identifier injection in source/target specs | `table`, `columns[]`, `watermarkColumn`, `keyColumns[]` set to each of: `customers; DROP TABLE x`, `customers--`, `"customers"`, `customers/**/`, `public.customers` (schema hop when not configured), `pg_catalog.pg_authid`, `ｃｕｓｔｏｍｅｒｓ` (full-width), `customers\u0000`, 129-char name, empty | 400 at flow save (`INVALID_IDENTIFIER`) and refused again in the connector before any SQL is sent (no statement in the DB log) | UNIT `JdbcIdentifiersTest`; CONN-IT `DbConnectorConnIT#identifierInjectionRefused` per vendor (SE4) |
| S8 | No free SQL anywhere | Any field accepting `SELECT`, `WHERE`, `ORDER BY` text | Field does not exist in the API (unknown properties rejected); `query` in `sourceSpec` → 400 | H2-IT `IntegrationFlowControllerTest#freeSqlRejected` (SE4) |
| S9 | Read-only sessions | Inbound flow on a reader account that *could* write | Connector sets `readOnly=true`; a write attempt inside the read path fails (PostgreSQL: `cannot execute … in a read-only transaction`) | CONN-IT (SE4); simulator reader role has `default_transaction_read_only` |
| S10 | JDBC URL option injection | `database` = `bank?socketFactory=…`, `sslmode=disable&loggerFile=/tmp/x`, `host` containing `/` or `@` | 400; only the per-vendor allow-listed options reach the driver | UNIT `JdbcIdentifiersTest#urlOptionsAllowListed` (SE4) |

## 3. SFTP

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S11 | Unknown host key | Connection without `hostKeyFingerprint` | 400 at save (pin required) | H2-IT (SE4) |
| S12 | Host key mismatch | Pin `SHA256:AAAA…` (any other fingerprint) against the simulator | Test/run fails **before authentication** (`HOST_KEY_MISMATCH`); the password is never sent | CONN-IT `SftpConnectorConnIT#hostKeyMismatchRefused` (SE4); E2E via simulator |
| S13 | Path traversal | `folder` = `../`, `inbound/../../etc`, `/etc`, `inbound/%2e%2e/`, symlink inside the folder pointing outside; `fileNamePattern` = `../x.csv`, `a/b.csv` | 400 at save where static; at run time the resolved path must stay under the configured root, else the run fails and nothing is read/written | UNIT `SftpPathsTest`; CONN-IT (SE4) |
| S14 | Size cap | Inbound file larger than the cap | File skipped, run event `FILE_TOO_LARGE`, file left in place (not moved to `processed/`) | CONN-IT (SE4) |
| S15 | Atomic write | Outbound upload interrupted | Only `*.tmp` remains; the final name appears only after rename | CONN-IT (SE4) |

## 4. Idempotency

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S16 | Replay returns the same job | `POST /api/integration/v1/records` twice with the same `Idempotency-Key` and byte-identical body | Same status and the **same `jobId`**; one `IngestionJob` row | H2-IT `IdempotencyTest`; E2E AC-INT-24 (SE2) |
| S17 | Key reuse with another body | Same key, different `records` (or different `templateId`) | **409** `IDEMPOTENCY_KEY_REUSED`; no new job | H2-IT (SE2); E2E AC-INT-24 |
| S18 | Keys are per client and per bank | Client A and client B (same or other bank) use the same key | Independent; no cross-client replay, no information about the other client's job | H2-IT, PG-IT `IntegrationApiRlsPgIT` (SE2) |
| S19 | Outbound dedupe | Dispatcher retries a delivery whose first attempt succeeded but timed out | Same `Idempotency-Key` (run + batch hash) is sent; the simulator's `/api/results` answers `replayed: true`; one row in the bank | CONN-IT + E2E (SE4) |

## 5. Webhook signatures

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S20 | Signature format | Any outbound webhook | `X-MoDocument-Timestamp` (unix seconds) and `X-MoDocument-Signature: sha256=hex(HMAC_SHA256(secret, ts + "." + rawBody))`; the simulator's verifier accepts it | UNIT `WebhookSignerTest` (SE4); E2E AC-INT-13 (`bankRest.received()` all `valid`) |
| S21 | Stale timestamp | Delivery with a timestamp 301 s old (replayed capture) | Rejected by the documented verifier (`stale-timestamp`); the dispatcher signs **at send time**, so a retry carries a fresh timestamp | node:test `signature.test.mjs` (QA, live); UNIT `WebhookSignerTest#signsAtSendTime` (SE4) |
| S22 | Tampered body / wrong secret | Body changed after signing; other secret | `signature-mismatch`; constant-time comparison | node:test (QA, live) |
| S23 | Secret rotation | Rotate the webhook secret | Old secret stops verifying after the overlap; never logged | UNIT (SE4/SE1) |
| S24 | Retries and dead-letter | Receiver answers 401/500 N times | Exponential back-off via the outbox; after N attempts `DEAD`, audited `INTEGRATION_DELIVERY_DEAD`, HIGH alert `integration-dead-letter`; replay from the UI resends | H2-IT `OutboundOutboxTest` (SE4); E2E run detail |

## 6. Client authentication, revocation, mTLS

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S25 | Revoked client | Revoke (`POST …/api-clients/{id}/revoke`) while a token is live | Next call **401** immediately (no TTL wait): `RevocationValidator` client branch checks status/`token_ver` | H2-IT `ClientRevocationTest`; E2E AC-INT-25 (SE2) |
| S26 | Expired client | `expiresAt` in the past (or passes during a live token) | Token endpoint 401; API key 401; live JWT 401 on the next call | H2-IT (SE2); E2E AC-INT-25 |
| S27 | Wrong secret / key | Bad `client_secret`, bad API-key secret, unknown prefix | 401 with the same body for all three (no oracle); `API_CLIENT_AUTH_FAILED` audited; `api-client-auth-failures` alert at 10/5 min | H2-IT `OAuthTokenEndpointTest`, `ApiKeyAuthenticatorTest` (SE2) |
| S28 | API-key request signing | Signed request with a stale timestamp, wrong body digest | 401 | H2-IT (SE2) |
| S29 | Spoofed mTLS headers | `X-Amzn-Mtls-Clientcert-*` sent to the public listener (not from the trusted ALB/proxy) | Headers stripped by nginx and ignored by the app → 401; `scripts/check-nginx-headers.sh` asserts the strip | H2-IT `MtlsClientAuthenticatorTest#untrustedProxyIgnored` (SE2); nginx check (SE5); E2E AC-INT-21 |
| S30 | Cert not registered / wrong bank | Valid cert of bank B's client presented for bank A's job | 404 (cross-bank), never another client's identity | H2-IT (SE2) |
| S31 | Source IP allow-list | Client with `allowedCidrs=["203.0.113.0/24"]` calling from elsewhere | 401/403 `CLIENT_IP_NOT_ALLOWED`; audited | H2-IT `IntegrationApiGuardsTest` (SE2) |

## 7. Masking and data leaving the tenant

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S32 | Masking on by default | New flow without `maskPii` | `FlowView.maskPii=true`; outbound EMAIL field arrives masked (`a***@…`) in the bank | H2-IT `PayloadBuilderMaskingTest`; E2E AC-INT-14 (SE4) |
| S33 | Turning masking off is SECURITY tier | `PUT …/flows/{id}` with `maskPii:false` on an enabled outbound flow; bank setting `integration.outboundPiiMasking=false` | 202 maker/checker (flow: `INTEGRATION_FLOW_ENABLE` widening; setting: `SETTING_UPDATE`) | H2-IT (SE4, SE1) |
| S34 | Export caps apply | Outbound run above `ExportGuard` cap / row cap | Truncated or refused per policy; `EXPORT_RUN` audited with the flow id | H2-IT (SE4) |
| S35 | Results endpoint is masked and capped | `GET /api/integration/v1/jobs/{id}/results` | Masked per client setting; capped; only VALIDATED rows; `/documents/{id}` only for released documents | H2-IT `IntegrationApiV1Test` (SE2) |

## 8. Outbound needs a second admin

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S36 | Enable outbound | `POST …/flows/{id}/enable` (OUTBOUND) | 202 `INTEGRATION_FLOW_ENABLE`; flow stays disabled until a **different** admin approves; self-approval 409 | H2-IT `FlowEnableApplierTest`; E2E AC-INT-10 (SE4) |
| S37 | Widening an enabled outbound flow | Add a PayloadKind, change the target, turn masking off, raise the row cap | 202 again; the old configuration keeps running until approval | H2-IT (SE4) |
| S38 | Stale approval | Flow edited after the change was submitted (`flowVersion` moved) | Applier refuses (409 conflict), nothing enabled | H2-IT (SE4) |

## 9. Agent

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S39 | Local allow-list | Cloud task asks the agent for `integration.staging_results` / `core.customer` / a host or folder not in the bank's local config | Agent refuses locally, reports `FAILED` with "not allowed"; no query is sent to the bank DB | UNIT `AllowListTest` (agent module); E2E AC-INT-30 (SE5) |
| S40 | Credentials never leave the bank | Agent-mode connection with `secretRef` | No bank password in any request to the cloud, any cloud table (`IntegrationConnection`, `AuditEvent`, `IntegrationRunEvent`) or the backend log | CONN-IT + E2E AC-INT-30 (SE5) |
| S41 | Enrolment token | Reuse the token; use it after 15 min; guess one | Second use 401/409; expired 401; enrolment rate-limited; token stored hashed (`EnrolmentTokenHash`) | H2-IT `AgentEnrolmentTest` (SE5) |
| S42 | Agent client scope | Agent's client token on `/api/integration/v1/**` or a user route; a SYSTEM client on `/api/agent/v1/**` | 403 (agent holds only `AGENT_CONNECT`; system clients lack it) | H2-IT `ClientScopeEnforcementTest` (SE2/SE5) |

## 10. User token vs client token

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S43 | Client token on user routes | Client JWT (`typ=client`) on `/Templates/GetAll`, `/api/admin/**`, `/api/auth/me` | 403 (scopes ∩ role grant has none of those permissions) | H2-IT `ClientScopeEnforcementTest` (SE2); E2E AC-INT-19 |
| S44 | User token on client routes | An ADMIN's access token on `/api/integration/v1/records` | 403 — `INTEGRATION_*` are client-only (`ADMIN = complementOf(PLATFORM_SET ∪ CLIENT_SCOPES)`) | H2-IT (SE2) |
| S45 | Forged claims | User JWT with `typ=client` added, or client JWT with `role=ADMIN` | 401 (signature) or the claim is ignored; `PermissionChecker` derives the role from the principal type, never from a free claim | H2-IT (SE2) |
| S46 | Scope narrowing | Client with `INTEGRATION_RESULTS` only posts records | 403 | H2-IT (SE2) |

## 11. Secrets at rest and in logs

| ID | Case | Input | Expected | Level · test (owner) |
|---|---|---|---|---|
| S47 | Write-only secrets | `GET` connections / api-clients after setting a secret | Views carry `secretSet:true` only; no secret in any response, audit row (`OldValues/NewValues`), pending-change payload or log line | H2-IT `ConnectionSecretLeakTest` (SE1); E2E AC-INT-02 |
| S48 | Encryption at rest | Read `IntegrationConnection.SecretCiphertext` as the owner | AES-256-GCM ciphertext with key version; bank-derived key (HKDF bankId): ciphertext of bank A cannot be opened under bank B | UNIT `SecretVaultTest`; PG-IT `SecretAtRestPgIT` (SE1) |
| S49 | Client secret storage | `ApiClientSecret` rows | Argon2id hashes only; the plain secret is readable once (`initial-secret`, maker only, 24 h) then 404/410 | H2-IT `ApiClientLifecycleTest` (SE2); E2E AC-INT-18 |

## Exit criteria

All S-cases automated at their stated level and green in CI; the E2E groups
in `integration-hub.spec.js` switched on (`E2E_WAVE3=1`) and green against
the bank simulator; no open P1/P2 security bug. QA re-runs ZAP baseline on
`/api/integration/v1/**` in Wave 4 (test plan AC-X-04).
