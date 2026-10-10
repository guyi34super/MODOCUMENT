# MoDocument Integration API v1: developer guide for bank IT

This guide is for engineers who connect a bank's own systems (core banking, a
data warehouse, an ESB, a scheduler) to MoDocument. Those systems can:

- **send data in**: JSON records or files, against a bank-shared template;
- **read results back**: job status, validated rows, rejected rows and reasons;
- **fetch released documents**;
- **follow an event feed** (jobs validated or failed, documents released).

Base URL: `https://<your MoDocument host>/api/integration/v1`. The OpenAPI
spec of the whole API is at `/v3/api-docs` (an authenticated bank user can
read it). A ready-made Postman collection is in
[`modocument-integration-v1.postman_collection.json`](modocument-integration-v1.postman_collection.json).

## 1. API clients

Each calling system is an **API client** of exactly one bank. A bank admin
with the `integration:admin` permission creates it under
**Administration → Integrations → API clients**:

1. Pick a name, the **authentication method** (OAuth2 client credentials,
   API key or mutual TLS), the **scopes**, optionally the **allowed IP
   ranges** (CIDRs), and an expiry date (default one year, at most three).
2. A **second admin approves** the request (maker/checker,
   `API_CLIENT_CREATE`). Nothing exists before that approval.
3. The admin who made the request (the maker) can then **reveal the secret
   once**, within 24 hours. It is never shown again, and MoDocument stores
   only an Argon2id hash. If the secret is lost, **rotate** the client.

| Scope | Allows |
|---|---|
| `INTEGRATION_INGEST` | `GET /templates`, `POST /records`, `POST /files` |
| `INTEGRATION_RESULTS` | `GET /jobs/{id}`, `/jobs/{id}/results`, `/jobs/{id}/errors` |
| `INTEGRATION_DOCS` | `GET /documents/{id}` |
| `INTEGRATION_EVENTS` | `GET /events` |

Grant each system only the scopes it needs. A call outside the client's
scopes gets **403 `INSUFFICIENT_SCOPE`**.

What every method enforces on **every** request:

- the client is ACTIVE and not expired;
- the bank is not suspended;
- the user who created the client is still an enabled user of the bank. When
  that person leaves (disabled or removed), the client stops working; create
  a new client under an active administrator. Agent clients have no creator
  and are not affected;
- the method is still allowed for the bank (the platform can switch a method
  off);
- the caller's IP is inside the allowed CIDRs, when any are set.

Revoking a client stops it at once: every key, certificate and issued token
fails on its next request.

Jobs created by a client belong to the user who created the client. They use
**bank-shared templates** only (a template the bank published with "Share with
bank").

### Rotation

**Rotate** issues a new secret, shown once. The previous secret keeps working
for **24 hours**, so the system can be redeployed without downtime. A
certificate client cannot be rotated: register a new client with the new
certificate, switch over, then revoke the old one.

## 2. Authentication

### 2a. OAuth2 client credentials (recommended)

You receive a `client_id` (`mdc_…`) and a `client_secret`. Exchange them for a
short-lived access token (5–15 minutes, set by the bank), then send it as a
Bearer token. Request a new token when the current one expires (`expires_in`,
in seconds). Do not request a token per call.

```bash
# client_secret_basic (preferred)
curl -s https://modocument.example.com/api/integration/oauth/token \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials

# client_secret_post
curl -s https://modocument.example.com/api/integration/oauth/token \
  -d grant_type=client_credentials \
  -d client_id="$CLIENT_ID" -d client_secret="$CLIENT_SECRET"

# optional: a narrower token (space-separated subset of the client's scopes)
curl -s https://modocument.example.com/api/integration/oauth/token \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials -d "scope=INTEGRATION_EVENTS"
```

```json
{"access_token":"eyJ…","token_type":"Bearer","expires_in":900,"scope":"INTEGRATION_INGEST INTEGRATION_RESULTS"}
```

```bash
curl -s https://modocument.example.com/api/integration/v1/templates \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

The token endpoint follows RFC 6749:

| Status | `error` | Meaning |
|---|---|---|
| 400 | `invalid_request` | Missing `grant_type`, or two client authentication methods at once |
| 400 | `unsupported_grant_type` | Only `client_credentials` is supported |
| 400 | `invalid_scope` | The requested scope is not a subset of the client's scopes |
| 401 | `invalid_client` | Unknown client, wrong secret, revoked, expired, creator disabled or removed, IP not allowed, method disabled |
| 429 | `slow_down` | Too many token requests from this address (see `Retry-After`) |

Answers carry `Cache-Control: no-store`. When client authentication fails,
the response does not say which check failed. The bank's admins see the
reason in the audit trail (`API_CLIENT_AUTH_FAILED`).

### 2b. API key

You receive one string, `mdk_<prefix>.<secret>`. Send it on every call:

```bash
curl -s https://modocument.example.com/api/integration/v1/templates \
  -H "Authorization: ApiKey $API_KEY"
```

An API key cannot be exchanged for a token. Treat it like a password: keep it
in your secret store, never in source code or URLs.

### 2c. Mutual TLS (client certificate)

The bank admin uploads the system's **client certificate** (PEM) when
creating the client. MoDocument keeps its SHA-256 fingerprint and subject; a
certificate can belong to only one client.

- The TLS handshake happens at MoDocument's mTLS endpoint
  (`api.<domain>`). The load balancer verifies the certificate against the
  bank's trust store and forwards it to the application.
- The application accepts the forwarded certificate **only** from its trusted
  proxy. Headers sent by anyone else are ignored.
- Expired or not-yet-valid certificates are refused.

```bash
# Call the API directly with the certificate…
curl -s https://api.modocument.example.com/api/integration/v1/templates \
  --cert client.crt --key client.key

# …or get a certificate-bound token (RFC 8705): it is accepted only on
# connections that present the same certificate.
curl -s https://api.modocument.example.com/api/integration/oauth/token \
  --cert client.crt --key client.key \
  -d grant_type=client_credentials
```

Renew the certificate before it expires by registering a new client.

## 3. Idempotency (every POST)

Every `POST` needs an `Idempotency-Key` header: up to 255 printable ASCII
characters, unique per logical request. A UUID is a good choice.

- **Same key, same request**: you get the **stored answer** (same status, same
  `jobId`), with `Idempotent-Replayed: true`. Nothing is created twice, so
  network retries are safe.
- **Same key, different request**: **409 `IDEMPOTENCY_KEY_REUSED`**.
- **Same key while the first request is still running**: **409
  `IDEMPOTENCY_KEY_IN_PROGRESS`**. Retry shortly.
- If a request fails (4xx/5xx), its key is released, so a corrected retry may
  reuse it.
- Keys are remembered for **24 hours**, per client.

## 4. Endpoints

### `GET /templates` (INTEGRATION_INGEST)

```json
{"templates":[{"templateId":12,"name":"Loan book","description":"…",
  "fields":[{"name":"AccountNo","dataType":"string"},{"name":"Amount","dataType":"decimal"}]}]}
```

### `POST /records` (INTEGRATION_INGEST)

```bash
curl -s https://modocument.example.com/api/integration/v1/records \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"templateId":12,"externalBatchId":"EOD-2026-10-10",
       "records":[{"AccountNo":"001-7781","Amount":1250.50},
                  {"AccountNo":"001-7782","Amount":99}]}'
```

The answer is **202** `{"jobId": 731}`, with `Location: /api/integration/v1/jobs/731`.

- Record values must be strings, numbers, booleans or null. Nested objects
  are refused.
- Limits: 10 000 records per request and a 10 MB body (413
  `PAYLOAD_TOO_LARGE` above that).
- `externalBatchId` is optional, at most 200 characters. It is kept with the
  job for your own reconciliation.

### `POST /files` (INTEGRATION_INGEST)

```bash
curl -s https://modocument.example.com/api/integration/v1/files \
  -H "Authorization: ApiKey $API_KEY" \
  -H "Idempotency-Key: $(uuidgen)" \
  -F templateId=12 -F file=@loanbook.xlsx
```

The answer is **202** `{"jobId": 732}`. The file goes through the normal
pipeline (parse, map, validate), so AI or code extraction applies.

- Limit: 25 MB per file.
- The same file (same name and same content) uploaded twice is a 409.

### `GET /jobs/{id}` (INTEGRATION_RESULTS)

```json
{"jobId":731,"status":"COMPLETED","currentStep":"VALIDATED","parsedBy":"CODE",
 "counts":{"rows":2,"valid":1,"rejected":1},"errorMessage":null}
```

Poll every few seconds until `currentStep` is `VALIDATED` (or `status` is
`FAILED`), or follow `/events`. Until then `counts.valid` and
`counts.rejected` are `null`.

### `GET /jobs/{id}/results?format=json|csv` (INTEGRATION_RESULTS)

Returns the validated rows. Fields classified as personal data are
**masked**. Above the bank's export row cap the read is refused with **403
`EXPORT_CAP_EXCEEDED`** (`cap` and `rows` are in the body). Every read is
logged as an export (`EXPORT_RUN`).

```json
{"jobId":731,"columns":["AccountNo","Amount"],"rows":[{"AccountNo":"001-7781","Amount":"1250.50"}],"count":1}
```

### `GET /jobs/{id}/errors` (INTEGRATION_RESULTS)

```json
{"jobId":731,"count":1,"rejected":[{"row":2,"values":{"AccountNo":"001-7782","Amount":"x"},
  "errors":[{"field":"Amount","message":"Amount must be a number","rule":"DATA_TYPE"}]}]}
```

`row` is 1-based, in the order you sent the records. Row values are masked
like the results.

Both `/results` and `/errors` answer **409 `JOB_NOT_READY`** until the job is
validated.

### `GET /documents/{id}` (INTEGRATION_DOCS)

Only documents in status `RELEASED` (after the four-eyes release) exist for
the API. Any other id is 404.

```json
{"documentId":55,"title":"Credit memo 2026-Q3","status":"RELEASED",
 "releasedAt":"2026-10-09T15:02:11","formats":["docx","pdf"]}
```

`GET /documents/{id}?format=pdf` (or `docx`) downloads the released file
exactly as it was generated.

### `GET /events?after=<cursor>&limit=<n>` (INTEGRATION_EVENTS)

```json
{"events":[{"id":981,"type":"INGESTION_VALIDATED","occurredAt":"2026-10-10T14:05:12Z",
  "entity":{"type":"IngestionJob","id":"731"},"data":{"jobId":731}}],"next":981}
```

- Event types: `INGESTION_VALIDATED`, `INGESTION_FAILED`, `DOC_RELEASED`,
  `EXTRACTION_COMPLETED`.
- Start with `after=0`. Store `next` and send it as `after` on the next poll.
- Events come oldest first. `limit` defaults to 100 (maximum 500).
- When there are no new events, `next` stays the same.

## 5. Errors

Every error of `/api/integration/v1` has the same shape:

```json
{"error":"Job 99 not found.","code":"NOT_FOUND","message":"Job 99 not found.","requestId":"01a1…"}
```

Quote `requestId` when you contact support.

| Status | `code` | When |
|---|---|---|
| 400 | `INVALID_REQUEST`, `INVALID_JSON`, `INVALID_FORMAT`, `INVALID_CURSOR`, `FILE_REQUIRED`, `UNSUPPORTED_FILE_TYPE`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID` | The request is malformed |
| 401 | `CLIENT_AUTH_FAILED` (API key / certificate); an empty body with `WWW-Authenticate: Bearer error="invalid_token"` (token) | Missing, wrong, revoked or expired credential; creator disabled or removed; IP not allowed; method disabled |
| 403 | `INSUFFICIENT_SCOPE` | The client lacks the route's scope |
| 403 | `EXPORT_CAP_EXCEEDED` | More validated rows than the bank's export cap |
| 403 | (empty body) | A user token on the integration API, or a client credential on a user route |
| 404 | `NOT_FOUND` | Unknown id, **or an id of another bank** (never 403), a private template, or an unreleased document |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_KEY_IN_PROGRESS`, `JOB_NOT_READY`, `CONFLICT` | See above |
| 413 | `PAYLOAD_TOO_LARGE` | Body, record count or file over the limit |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` |
| 429 | `RATE_LIMITED` | More than 600 calls per minute for this client (see `Retry-After`) |
| 500 | `INTERNAL_ERROR` | Our fault: retry with backoff and quote `requestId` |

Retry policy: retry `429`, `5xx` and network errors with exponential backoff,
**reusing the same `Idempotency-Key`**. Do not retry other `4xx` unchanged.

## 6. Webhooks from MoDocument: signature verification

Outbound flows (configured by the bank admin) can push events and payloads to
your HTTPS endpoint. Every delivery is signed with the flow's shared secret:

```
X-MoDocument-Timestamp: 1791641313
X-MoDocument-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>
```

Verify **before** parsing the body:

1. Recompute the HMAC over `timestamp + "." + body`, using the **raw bytes**
   you received.
2. Compare it in constant time.
3. Reject timestamps more than **5 minutes** from your clock (replay
   protection).
4. Deliveries are retried with backoff and carry an idempotency key, so make
   your handler idempotent.

```python
import hashlib, hmac, time

def verify(secret: bytes, headers: dict, body: bytes, tolerance: int = 300) -> bool:
    ts = headers.get("X-MoDocument-Timestamp", "")
    sig = headers.get("X-MoDocument-Signature", "")
    if not ts.isdigit() or abs(time.time() - int(ts)) > tolerance:
        return False
    expected = "sha256=" + hmac.new(secret, ts.encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, sig)
```

```java
static boolean verify(byte[] secret, String ts, String signature, byte[] body) throws Exception {
    long now = java.time.Instant.now().getEpochSecond();
    if (ts == null || !ts.matches("\\d+") || Math.abs(now - Long.parseLong(ts)) > 300) {
        return false;
    }
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
    mac.update((ts + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    return java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            signature == null ? new byte[0]
                    : signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
}
```

```js
const crypto = require("crypto");
function verify(secret, ts, signature, rawBody) {
  if (!/^\d+$/.test(ts) || Math.abs(Date.now() / 1000 - Number(ts)) > 300) return false;
  const expected = "sha256=" + crypto.createHmac("sha256", secret)
    .update(ts + ".").update(rawBody).digest("hex");
  const a = Buffer.from(expected), b = Buffer.from(signature || "");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
```

## 7. Security checklist for bank IT

- Prefer **mutual TLS** or **OAuth2** over long-lived API keys. Set
  **allowed CIDRs** to your egress addresses.
- One client per system, with the smallest set of scopes.
- Store secrets in your vault. Rotate them on staff changes and at least
  yearly. Revoke the clients of decommissioned systems.
- Every call is audited with the client as the actor
  (`INTEGRATION_API_CALL`). Repeated authentication failures from one address
  raise a security alert.

## 8. Local testing

### Local mTLS listener

In AWS, the load balancer's `api.<domain>` listener terminates mutual TLS
(section 2c). For development, `docker-compose.mtls-dev.yml` adds a stand-in:
an nginx on `https://localhost:8443` with `ssl_verify_client optional`
against a **throwaway dev CA**. It serves only `/api/integration/**` and
`/api/agent/**`, like the real listener.

1. Generate the key material. It is created at runtime in `.mtls-dev/`,
   which git and Docker ignore; nothing is committed:

   ```bash
   scripts/dev-mtls-certs.sh                  # dev CA, server cert, client "agent"
   scripts/dev-mtls-certs.sh client bank-erp  # one more client certificate
   ```

   Each client gets `.mtls-dev/clients/<name>/<name>.crt` and `.key`, the
   same pair as `certificatePem` and `privateKeyPem` (the Connector Agent's
   secret-folder layout), and the SHA-256 fingerprint that MoDocument stores.
   Certificates are valid for 30 days (`MODOCUMENT_MTLS_DEV_DAYS`).
   `scripts/dev-mtls-certs.sh clean` deletes everything.

2. Start the stack with the overlay:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.mtls-dev.yml up -d
   ```

   The listener runs inside the app container's network namespace, as the
   frontend sidecar does on ECS. The app trusts client-certificate headers
   only from `127.0.0.1` (`MODOCUMENT_MTLS_TRUSTED_PROXY_CIDRS`). Browsers
   keep using `http://localhost:3000`, where those headers are always
   cleared.

3. Create an API client with **Mutual TLS** in the dashboard and upload
   `.mtls-dev/clients/bank-erp/bank-erp.crt`. A second admin approves it.
   Then call the API with that certificate:

   ```bash
   C=.mtls-dev/clients/bank-erp
   curl --cacert .mtls-dev/ca.crt --cert $C/bank-erp.crt --key $C/bank-erp.key \
     https://localhost:8443/api/integration/v1/templates
   curl --cacert .mtls-dev/ca.crt --cert $C/bank-erp.crt --key $C/bank-erp.key \
     https://localhost:8443/api/integration/oauth/token -d grant_type=client_credentials
   ```

4. A Connector Agent enrols with its own certificate
   (`agent/README.md`, "Authentication"):

   ```bash
   MODOCUMENT_URL=https://localhost:8443 \
   MODOCUMENT_AGENT_AUTH_METHOD=MTLS \
   MODOCUMENT_SECRETS_DIR=.mtls-dev/clients MODOCUMENT_AGENT_CLIENT_CERT_REF=agent \
   MODOCUMENT_AGENT_TRUSTED_CA_FILE=.mtls-dev/ca.crt \
   MODOCUMENT_ENROLMENT_TOKEN=mda1.… \
     java -jar agent/target/modocument-agent-<version>.jar
   ```

   From a container, use `https://host.docker.internal:8443`; the server
   certificate covers that name too.

What it behaves like, and where it differs from the ALB:

| Request | Result |
|---|---|
| Certificate signed by the dev CA | Forwarded as `X-Amzn-Mtls-Clientcert-Leaf` (URL-encoded PEM). The app maps it to the client by fingerprint. |
| No certificate | Passed through without the header (enrolment, OAuth2 clients). The ALB in verify mode refuses such a connection instead. |
| Certificate from another CA | 400 from nginx; the request never reaches the app. |
| `X-Amzn-Mtls-*` headers sent by the caller | Replaced by what nginx verified, so a planted header never reaches the app. |
| Any path outside `/api/integration/` and `/api/agent/` | 404 |

The dev CA, its keys and every certificate signed by it are for one
developer machine only. Never add the dev CA to a staging or production
trust store.
