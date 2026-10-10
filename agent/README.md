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
| `JAVA_TOOL_OPTIONS` | — | e.g. `-Dhttps.proxyHost=proxy.bank.local -Dhttps.proxyPort=3128` for an outbound proxy. |

The agent long-polls MoDocument for work (30 s), sends a heartbeat every
30 s and backs off exponentially (with jitter) when MoDocument or a bank
system is unreachable.

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
| `DATABASE` | Read-only `SELECT` of listed columns from a listed table or view, by identifier (never free SQL), with an optional watermark column and a row cap | `INSERT`, or the vendor's UPSERT, of one batch in one transaction | PostgreSQL, MariaDB/MySQL, SQL Server, Oracle |
| `REST_API` | `GET` a JSON array (no pagination in this version) | `POST`/`PUT` JSON in batches with `Idempotency-Key` | No redirects; 20 MB response cap |
| `SFTP` | Files matching a glob (≤ 50 MB each), then moved to `processed/` or deleted | Upload to a temporary name, then rename | Host key **pinned** (`SHA256:…`); unknown keys are refused |

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
mvn -B -f agent/pom.xml verify                     # tests
docker build -t modocument-agent agent             # image
```

This is a standalone Maven project, not a module of the root build. Its
version follows the MoDocument release (`scripts/check-version-sync.sh`).
