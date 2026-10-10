# Bank simulator (Wave 3 E2E harness)

The "bank" side of the Bank Integration Hub end-to-end tests: a bank
database, a bank REST API with a webhook receiver, and an SFTP drop. The
MoDocument stack talks to it exactly as it would talk to a real bank, and the
specs assert on the bank side (rows in the staging table, a verified webhook,
a file in `outbound/`). **Everything here is TEST ONLY**: throwaway data,
per-run credentials, and an SFTP host key that is committed on purpose so a
connection can pin it.

Specs: `e2e/tests/phase2/integration-hub.spec.js` (all `test.fixme('waiting
for Wave 3 merge')` until the features merge). Security cases:
[`docs/qa/integration-security-tests.md`](../../docs/qa/integration-security-tests.md).

```
bank-simulator/
  docker-compose.bank-sim.yml   bank-db + bank-rest + bank-sftp
  db/bank-sim.sql               schema, seed and least-privilege roles (psql variables)
  db/00-init.sh                 postgres first-boot wrapper around bank-sim.sql
  rest-stub/server.mjs          REST stub (Node, no dependencies)
  rest-stub/signature.mjs       webhook signature verifier (the bank side of the contract)
  rest-stub/*.test.mjs          node:test suites (20 tests)
  sftp/host-keys/*.TEST-ONLY    pinned host keys + fingerprints + known_hosts
  sftp/sftp.d/                  start-up scripts (pin the keys, lay out folders)
  sftp/seed/inbound/            files waiting for an inbound SFTP flow
```

## What the bank exposes

| Piece | Details |
|---|---|
| **Database** (PostgreSQL 16) | `core.customer` (internal, PII incl. `national_id`), `core.ledger` (internal). `integration.customers` — the **view** MoDocument reads (no national id; watermark `updated_at`, 25 rows, one day apart). `integration.staging_results` — the **table** MoDocument writes (unique `external_ref` for UPSERT). Roles: reader = `SELECT` on the view only, `default_transaction_read_only`; writer = `SELECT/INSERT/UPDATE` on the staging table only. Neither can touch `core.*`. |
| **REST** (`rest-stub/server.mjs`) | `POST /oauth/token` (client credentials, `client_secret_basic` or form) → bearer token. `GET /api/customers?page=&size=` (`{items, page, size, total, next}` + `Link: rel="next"`), or `?cursor=` paging. `POST /api/results` (bearer **or** `X-Api-Key`) captures records; an `Idempotency-Key` replay returns the first answer (200, `replayed: true`), the same key with another body is **409**. `POST /webhooks/modocument` verifies the signature and the 5-minute window: 204 or **401** `{reason}`. |
| **SFTP** (`atmoz/sftp`) | user `bank` (password per run), `inbound/` seeded with `customers-2024-06-01.csv`, `inbound/processed/`, `outbound/`. Host keys pinned from `sftp/host-keys/` (ed25519 + RSA); fingerprints in `host-key-fingerprints.TEST-ONLY.txt`. |

Control plane for specs (never for MoDocument): `GET /__received` (webhook
deliveries with `valid` and `reason`), `GET /__captured` (pushed records),
`GET /__stats`, `POST /__reset`, `POST /__customers {count}`, `GET /health`.
From a spec, use `lib/banksim.js` (`bankRest.*`, `stagingRows()`,
`touchCustomers()`, `eventually()`).

### Webhook signature (what the receiver checks)

```
X-MoDocument-Timestamp: <unix seconds>
X-MoDocument-Signature: sha256=<hex(HMAC_SHA256(secret, timestamp + "." + rawBody))>
```

Refusal reasons: `missing-signature`, `missing-timestamp`, `bad-timestamp`,
`stale-timestamp` (older than 300 s), `future-timestamp` (more than 300 s
ahead), `bad-signature-format`, `signature-mismatch`. The comparison is
constant-time. `npm run test:bank-sim` (in `e2e/`) runs the node:test suites.

## Run it

### Locally, without Docker (bank DB + REST; no SFTP)

```bash
E2E_BANK_SIM=1 e2e/run-local.sh                 # starts it next to the stack
E2E_WAVE3=1 E2E_BANK_SIM=1 e2e/run-local.sh     # ... and switches the Wave-3 specs on
```

`run-local.sh` creates `bank_sim_<run>` on the same scratch PostgreSQL with
per-run roles and passwords, starts the REST stub on a free port, exports
`E2E_BANK_SIM_*` to Playwright, and drops it all on exit. The SFTP specs skip
(`E2E_BANK_SIM_SFTP_PASSWORD` unset).

### With Docker

```bash
umask 077; mkdir -p e2e/.run
{
  echo "BANK_SIM_DB_ADMIN_PASSWORD=$(openssl rand -hex 24)"
  echo "BANK_SIM_DB_READER_PASSWORD=$(openssl rand -hex 24)"
  echo "BANK_SIM_DB_WRITER_PASSWORD=$(openssl rand -hex 24)"
  echo "BANK_SIM_CLIENT_ID=bank-sim-ci"
  echo "BANK_SIM_CLIENT_SECRET=$(openssl rand -hex 24)"
  echo "BANK_SIM_API_KEY=$(openssl rand -hex 24)"
  echo "BANK_SIM_WEBHOOK_SECRET=$(openssl rand -hex 32)"
  echo "BANK_SIM_SFTP_PASSWORD=$(openssl rand -hex 16)"
} > e2e/.run/bank-sim.env
docker compose -f e2e/bank-simulator/docker-compose.bank-sim.yml \
  --env-file e2e/.run/bank-sim.env up -d --wait
```

Published on loopback only: bank DB `127.0.0.1:55433`, REST
`http://127.0.0.1:18090`, SFTP `127.0.0.1:2222`.

**Inside the E2E compose stack** (Wave 3 switch-on, SE5 owns the CI job):
add `include: [e2e/bank-simulator/docker-compose.bank-sim.yml]` to
`docker-compose.e2e.yml`, put the variables above in the job's env file, and
give the `e2e` service:

```
E2E_WAVE3=1
E2E_BANK_SIM_REST_URL=http://bank-rest:18090
E2E_BANK_SIM_DB_URL=postgres://bankadmin:${BANK_SIM_DB_ADMIN_PASSWORD}@bank-db:5432/bank
E2E_BANK_SIM_DB_HOST_FOR_APP=bank-db   E2E_BANK_SIM_DB_PORT_FOR_APP=5432   E2E_BANK_SIM_DB_NAME=bank
E2E_BANK_SIM_DB_READER=modoc_reader    E2E_BANK_SIM_DB_READER_PASSWORD=${BANK_SIM_DB_READER_PASSWORD}
E2E_BANK_SIM_DB_WRITER=modoc_writer    E2E_BANK_SIM_DB_WRITER_PASSWORD=${BANK_SIM_DB_WRITER_PASSWORD}
E2E_BANK_SIM_SFTP_HOST=bank-sftp       E2E_BANK_SIM_SFTP_PORT=22
E2E_BANK_SIM_SFTP_USER=bank            E2E_BANK_SIM_SFTP_PASSWORD=${BANK_SIM_SFTP_PASSWORD}
E2E_BANK_SIM_WEBHOOK_SECRET=${BANK_SIM_WEBHOOK_SECRET}   E2E_BANK_SIM_API_KEY=${BANK_SIM_API_KEY}
```

The app reaches `bank-db`, `bank-rest` and `bank-sftp` on private addresses,
so each needs an approved egress rule (spec `AC-INT-17b` requests and
approves them through the product).

## The committed host key (TEST ONLY)

`sftp/host-keys/ssh_host_{ed25519,rsa}_key.TEST-ONLY` are private keys **by
design**: an SFTP connection pins the server's host-key fingerprint, so the
fingerprint must be known before the server starts. They protect nothing —
the server holds only generated test files — and must never be reused
anywhere else. gitleaks flags them as private keys; `.gitleaks.toml`
allow-lists exactly these two paths. To rotate: re-generate both keys, update
`host-key-fingerprints.TEST-ONLY.txt` and `known_hosts.TEST-ONLY`.

A **mismatch** test (host key changed) needs no second key: pin any other
fingerprint, e.g. `SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`, and
expect the connector to refuse before authenticating.

## Oracle, SQL Server, MariaDB

Not here: the connector contract tests for the other three vendors run in CI
under `-Pconnectors-it` with Testcontainers (SE4, test plan AC-INT-05). The
same `bank-sim.sql` layout (view + staging table + reader/writer) is the
reference for those fixtures.
