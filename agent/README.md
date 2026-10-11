# MoDocument Connector Agent

A small service the bank runs **inside its own network** to connect its
databases, internal REST APIs and SFTP servers to MoDocument. It has **no
inbound port**: it only makes outbound HTTPS calls to MoDocument, plus the
calls to the bank systems that the bank's own allow-list permits.

Architecture, security model and the enrolment flow:
[`docs/integration/agent.md`](../docs/integration/agent.md).

## Install

1. In MoDocument, an integration admin opens **Integrations → Agents →
   Register agent**. A second admin approves the registration.
2. The admin who registered it opens the agent and copies the **one-time
   enrolment token** and the `docker run` command. The token is shown
   once and expires after 15 minutes.
3. On the bank's host, create the allow-list and the secrets (see below),
   then run the command, for example:

```sh
docker run -d --name modocument-agent --restart unless-stopped \
  --read-only --tmpfs /tmp \
  -v modocument-agent-state:/var/lib/modocument-agent \
  -v /etc/modocument-agent:/etc/modocument-agent:ro \
  -e MODOCUMENT_URL=https://modocument.example.com \
  -e MODOCUMENT_ENROLMENT_TOKEN=mda1.12.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx \
  ghcr.io/guyi34super/modocument-agent:1.0.0
```

On the first start the agent trades the token for its own client
credentials and stores them in the state volume (`credentials.json`, mode
`0600`). The token is then used up. Remove `MODOCUMENT_ENROLMENT_TOKEN` from
your run configuration afterwards; it stays visible in `docker inspect`
until the container is recreated, but it can no longer be used.

Without Docker: `java -jar modocument-agent-1.0.0.jar` (Java 21) with the
same environment variables. The jar is attached to every GitHub Release.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MODOCUMENT_URL` | required | MoDocument base URL. Must be `https://`. |
| `MODOCUMENT_ENROLMENT_TOKEN` | — | One-time token. Needed on the first start only. |
| `MODOCUMENT_AGENT_NAME` | host name | Name reported at enrolment (logs and audit). |
| `MODOCUMENT_AGENT_STATE_DIR` | `/var/lib/modocument-agent` | Issued credentials and the health file. Keep it on a persistent volume. |
| `MODOCUMENT_AGENT_ALLOWLIST` | `/etc/modocument-agent/allowlist.yml` | The bank's allow-list. **Missing = every task is refused.** |
| `MODOCUMENT_SECRETS_DIR` | `/etc/modocument-agent/secrets` | Local secrets, one file or folder per `secretRef`. |
| `MODOCUMENT_SECRET_<REF>` | — | A local secret in the environment (wins over the folder). |
| `MODOCUMENT_AGENT_MAX_BACKOFF` | `5m` | Longest wait between retries after errors. |
| `MODOCUMENT_AGENT_AUTH_METHOD` | `OAUTH2_CLIENT_CREDENTIALS` | How the agent authenticates to MoDocument: `OAUTH2_CLIENT_CREDENTIALS` or `MTLS` (see [Authentication](#authentication)). Chosen at enrolment. |
| `MODOCUMENT_AGENT_CLIENT_CERT_REF` | `agent-client-cert` | `MTLS` only: the local secret that holds the agent's certificate and key. |
| `MODOCUMENT_AGENT_TRUSTED_CA_FILE` | — | A PEM bundle that **replaces** the JDK trust store for calls to MoDocument (a private CA, local development). |
| `JAVA_TOOL_OPTIONS` | — | e.g. `-Dhttps.proxyHost=proxy.bank.local -Dhttps.proxyPort=3128` for an outbound proxy. |

The agent long-polls MoDocument for work (25 s, under the 30 s CloudFront origin timeout), sends a heartbeat every
30 s and backs off exponentially (with jitter) when MoDocument or a bank
system is unreachable.

## Authentication

The agent authenticates to MoDocument in one of two ways. It chooses at
enrolment, and the choice is stored with its credentials.

- **`OAUTH2_CLIENT_CREDENTIALS`** (default): MoDocument issues a client
  secret at enrolment. The agent keeps it in `credentials.json` and trades
  it for short-lived access tokens (`client_secret_basic`).
- **`MTLS`**: the agent uses its **own client certificate**. No secret is
  issued:
  1. The certificate goes to MoDocument at enrolment and is registered for
     the agent's client.
  2. The token endpoint authenticates the agent by the certificate in the
     TLS handshake (RFC 8705 `tls_client_auth`). It binds every access
     token to that certificate, so the agent presents it on every call.
  3. The agent must reach MoDocument through its mTLS listener
     (`https://api.<domain>`). The bank's CA must be in that listener's
     trust store (`api_mtls_ca_bundle_pem` in `infra/terraform`), and the
     agent's public address in `api_mtls_client_cidrs`.

  The certificate and its **unencrypted PKCS#8** key come from the local
  secret store, never from a file MoDocument knows about: a folder
  `<secrets dir>/agent-client-cert/` with the files `certificatePem` (leaf
  first, then any intermediates) and `privateKeyPem`
  (`-----BEGIN PRIVATE KEY-----`; convert other formats with
  `openssl pkcs8 -topk8 -nocrypt`). A JSON value
  `{"certificatePem": "…", "privateKeyPem": "…"}` in
  `MODOCUMENT_SECRET_AGENT_CLIENT_CERT` works too.

```sh
docker run … \
  -e MODOCUMENT_URL=https://api.modocument.example.com \
  -e MODOCUMENT_AGENT_AUTH_METHOD=MTLS \
  -v /etc/modocument-agent:/etc/modocument-agent:ro \
  ghcr.io/guyi34super/modocument-agent:<version>
# /etc/modocument-agent/secrets/agent-client-cert/{certificatePem,privateKeyPem}
```

To switch an enrolled agent between methods, or to replace its certificate,
revoke it and register it again: the certificate is part of the agent's
identity. An agent that enrolled with `MTLS` refuses to start without
`MODOCUMENT_AGENT_AUTH_METHOD=MTLS`, so it never silently falls back.

For local development against the compose mTLS listener, see
[`docs/integration/developer-guide.md`](../docs/integration/developer-guide.md#local-mtls-listener).

## Secrets

MoDocument stores only the **name** of a secret (`secretRef`) for each
agent-mode connection. The value lives only on the bank's host:

- environment variable `MODOCUMENT_SECRET_<REF>`: the ref in upper case,
  every character outside `A-Z0-9` replaced by `_`. For example, `core-db`
  becomes `MODOCUMENT_SECRET_CORE_DB`;
- or a file `<secrets dir>/<ref>`;
- or a folder `<secrets dir>/<ref>/` with one file per key.

A plain value is the connection's single secret: the database or SFTP
password, the bearer token, or the API key. A JSON object provides several
keys, for example `{"password": "…"}`, `{"token": "…"}`, `{"apiKey": "…"}`
or `{"privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----…"}`.

Secret values are never logged and never sent to MoDocument. If a driver
echoes one in an error message, the agent masks it before reporting.

## Allow-list

The bank controls this file, and the agent checks **every** task against it
before it opens a connection. Anything not listed is refused and reported
as a failed run. Even a compromised MoDocument account cannot make the
agent read other tables, hosts or folders.

```yaml
# /etc/modocument-agent/allowlist.yml (mount read-only)
destinations:            # host + ports any connection may reach
  - host: db.bank.local
    ports: [5432]
  - host: sftp.bank.local
    ports: [22]
  - host: crm.bank.local
    ports: [443]
database:
  tables:                # [schema.]table, exact, case-insensitive
    - modocument.v_customers_export     # a view the DBA prepared
    - staging.modocument_results        # where outbound results land
sftp:
  folders:               # absolute; sub-folders included
    - /exports/modocument
rest:
  pathPrefixes:          # optional; absent = any path on a listed host
    - /api/v1/
```

The file is re-read when it changes. A table name without a schema is
qualified with the connection's `schema` before it is compared.

## What the agent can do

| Type | Read (inbound) | Write (outbound) | Notes |
|---|---|---|---|
| `DATABASE` | Read-only `SELECT` of listed columns from a listed table or view, by identifier (never free SQL; names are quoted, so they are case-sensitive), with an optional watermark column and a row cap | `INSERT`, or the vendor's UPSERT, in one transaction per batch of `batchSize` (500) rows | PostgreSQL, MariaDB/MySQL, SQL Server, Oracle |
| `REST_API` | `GET` a JSON array at `recordsPath`, paginated (below); nested objects flatten to dotted keys | `POST`/`PUT` JSON in batches with `Idempotency-Key` | NONE, BASIC, BEARER, API key header, OAuth2 client credentials, MTLS; no redirects; 20 MB response cap |
| `SFTP` | Files matching a glob (≤ 50 MB each), moved to `processed/` or deleted once MoDocument accepted them; a larger file is skipped, logged and left in place | Upload to a temporary name, then rename; an existing file of the same name means the batch was delivered already | Host key **pinned** (`SHA256:…`); unknown keys are refused |

The connectors are MoDocument's own connector code (`connector-core`), the
same that runs for DIRECT connections, so a flow behaves the same through
the agent as without it.

### REST pagination

The flow's `sourceSpec.pagination` works the same way as with MoDocument's
direct REST connector:

| `type` | Settings | The agent… |
|---|---|---|
| `NONE` | — | makes one request |
| `PAGE` | `pageParam` (`page`), `sizeParam`, `pageSize` (100), `startPage` (1), `maxPages` | counts pages up and stops at a short page (or at an empty page when no size is set) or after `maxPages` |
| `CURSOR` | `cursorParam`, `cursorPath` (e.g. `meta.next` or `$.meta.next`), `sizeParam`, `pageSize` | sends the cursor from the previous answer and stops when it is missing, empty or repeats |
| `LINK` | `sizeParam`, `pageSize` | follows `Link: <…>; rel="next"`, but only on the same host and the **same path** (the path your allow-list approved); any other link fails the run |

Each page is at most 1000 records (MoDocument's limit per upload). The
flow's row cap still applies.

## Health

The container `HEALTHCHECK` runs `java -jar agent.jar healthcheck`. It
reports healthy while the last successful poll or heartbeat is less than
2 minutes old. The dashboard shows the agent as online, offline or
revoked, with its version.

## Upgrade

The agent never updates itself. When the dashboard shows **Update
available**, or the agent logs that MoDocument runs a newer version, pull
the new image and recreate the container with the same volumes:

```sh
docker pull ghcr.io/guyi34super/modocument-agent:<new version>
docker rm -f modocument-agent
docker run … ghcr.io/guyi34super/modocument-agent:<new version>   # same flags, no token
```

The credentials in the state volume carry over.

## Build

```sh
mvn -B -f connector-core/pom.xml install           # the shared connectors, first
mvn -B -f agent/pom.xml verify                     # tests
docker buildx build --build-context connector-core=connector-core \
    -t modocument-agent agent                      # image
```

This is a standalone Maven project, not a module of the root build. It
depends on `connector-core` (`../connector-core`, another standalone
project), which must be installed first; the image build takes its sources
as the named build context `connector-core`. Its version follows the
MoDocument release (`scripts/check-version-sync.sh`).
