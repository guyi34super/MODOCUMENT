# API versioning and deprecation policy

Control: **SEC-006** (versioned API catalogue, machine-readable spec validated in CI).
Gate: **E2** (API documented in OpenAPI). Related: [OWASP API Top 10 checklist](../security/owasp-api-top10.md).

## 1. The contract

| What | Where |
|---|---|
| Machine-readable spec, generated from the code (springdoc) | `GET /v3/api-docs` (JSON), `GET /v3/api-docs.yaml` |
| Route inventory (METHOD + path), pinned in CI | [`docs/api/openapi-routes.txt`](openapi-routes.txt) |
| CI check | `OpenApiContractTest` (part of `mvn -B verify`) |
| Interactive explorer | Swagger UI at `/swagger-ui`, **`local` profile only** |
| Hand-written `static/swagger/spec.yaml` | **Deprecated.** It is not maintained, already differs from the code, and will be removed. |

The spec cannot drift from the code because it is generated from it: paths, parameters, request
schemas and Bean Validation constraints come from the handler methods. `OpenApiContractTest` checks
the following on every build:

- the spec is a well-formed OpenAPI 3.1 document whose title and version come from the build (`pom.xml`);
- every handler route appears in the spec, and the spec contains nothing else;
- the sorted route inventory matches `docs/api/openapi-routes.txt` exactly. A route that is added,
  removed or given a different method fails CI until the file is regenerated and the diff reviewed;
- `bearerAuth` (HTTP bearer, JWT) is the global security scheme. The only anonymous operations are
  the login entry points (`/api/auth/login`, `/api/auth/refresh`, `/api/auth/mfa/verify`,
  `/api/auth/providers`) and the public synthetic uptime probe (`GET /api/probe/synthetic`,
  rate-limited per IP, ws18). Every other operation answers an anonymous call with **401**;
- every rate-limited operation documents **429** with a `Retry-After` header;
- every operation has an explicit controller `@Tag` and a stable `operationId`
  (`<controller><Method>`, for example `ingestionJobResume`).

To regenerate the inventory after a deliberate change:

```bash
mvn -Dtest=OpenApiContractTest -Dmodocument.contract.update=true test
git diff docs/api/openapi-routes.txt   # review against section 3 below
```

## 2. Versions

- **v1** is the current API: the unprefixed namespaces `/api/**`, plus the legacy CRUD paths
  `/Templates/**`, `/TemplateFields/**`, `/TemplateDataTypes/**`, `/ProcessStep/**`,
  `/ImportHistory/**`, `/ImportDetails/**` and `/Validation*/**`. The spec's `info.version` is
  the build version. It identifies the release, not the API generation.
- Paths are **URL-stable** within a version. A published path keeps its method, meaning and
  authentication model for the life of v1.
- A **breaking change ships under a new `/api/v2/...` namespace**, served alongside v1. v1 is not
  changed incompatibly in place.

## 3. What counts as additive vs. breaking

| Additive (allowed in v1, no notice needed) | Breaking (needs `/api/v2` + the deprecation window) |
|---|---|
| New endpoint or new method on an existing path | Removing or renaming a path, method or `operationId` |
| New **optional** request field or query parameter | Making an optional field or parameter required |
| New response field | Removing or renaming a response field, or changing its type or format |
| New enum value in a response (clients must tolerate unknown values) | Narrowing accepted input (stricter validation of previously valid data) |
| New error code on a path that already fails that way | Changing the success status code or the response media type |
| Raising a rate limit | Lowering a rate limit below what documented clients need |

**Security exception.** A fix for a vulnerability can break a caller without the deprecation
window, for example when it closes an authorization gap or tightens a limit that was being
abused. The fix is announced in the release notes and recorded in the change ticket.

The inventory diff in `docs/api/openapi-routes.txt` makes route-level changes visible in review.
Field-level changes appear in the `/v3/api-docs` diff between releases, which is deterministic
because keys are sorted (`springdoc.writer-with-order-by-keys`).

## 4. Deprecation (minimum 6 months)

1. **Mark it in code.** Annotate the handler `@Deprecated`, which springdoc renders as
   `deprecated: true`. Add `@Operation(description = "Deprecated since <release>; use <replacement>;
   removal not before <date>")`.
2. **Announce it** in the release notes and to the known integrators. The 6-month clock starts
   on the announcement date.
3. **Keep it working** for at least 6 months after the announcement. Its permission, tenant and
   rate-limit controls stay in force throughout.
4. **Remove it** only after the window ends. Removing it changes the route inventory, so
   `OpenApiContractTest` fails until `openapi-routes.txt` is regenerated. The reviewer checks the
   announcement date in that PR.

`static/swagger/spec.yaml` is under this policy: it was marked deprecated in workstream 17 and
can be removed after the window ends. It is not a route, so removing it does not affect the
inventory.

## 5. Exposure of `/v3/api-docs` (decision, workstream 17)

| Profile | Spec endpoint | Swagger UI |
|---|---|---|
| `local`, `test` (`permit-dev-paths: true`) | anonymous | `local` only |
| production (default) | **requires a valid bearer token** (any role) | off |
| any deployment that wants none | `MODOCUMENT_API_DOCS_ENABLED=false` (CI still validates the contract) | off |

Reasons for the production choice:

- **No anonymous reconnaissance.** An unauthenticated caller cannot list the route catalogue. The
  endpoint sits behind the same `authenticated()` wall as every non-login route
  (`SecurityConfig`), which `ApiDocsExposureTest` proves in a production-configured context:
  anonymous gets 401 and a `READ_ONLY` token gets 200.
- **Why not permission-gated.** The spec describes routes, not data. A signed-in user already has
  the route list, because the SPA bundle carries it. Every route still enforces its own
  `@RequiresPermission` and its bank and ownership scoping, and integrators in every role need
  the contract. A dedicated permission would add a role-matrix entry without making anything
  safer.
- **Why it is not in the permission coverage list.** The springdoc endpoints are served by the
  framework and carry no `@RequiresPermission`. `RoutePermissionCoverageTest` allowlists the
  `/v3/api-docs` and `/swagger` prefixes for that reason, and its comment records this decision.

## 6. Sandbox access (stub; to be completed before external integrators onboard)

- **Environment:** a non-production deployment loaded with synthetic data only, never a copy of
  production (see the SEC-008 notes in [`loadtest/README.md`](../../loadtest/README.md)).
- **Credentials:** issued per integrator as admin-created users (`READ_ONLY` or `READ_WRITE`),
  through the [JML runbook](../security/jml-runbook.md). No shared accounts. Access tokens last
  15 minutes and are renewed through `/api/auth/refresh`.
- **Limits:** the same per-user rate limits as production, so clients learn to honour `429` and
  `Retry-After`.
- **Contract:** `/v3/api-docs` on the sandbox host. `modocument.api.server-url`
  (`MODOCUMENT_API_SERVER_URL`) sets the `servers` entry.
- **Open items:** owner, request process, data-refresh schedule. These are tracked with the
  SEC-006 control.

## 7. Deprecated and removed routes (release 1.2.0, workstream 28)

**Deprecated.** The routes below still answer normally. Every response carries:

- `Deprecation: true`
- `Sunset: Thu, 01 Apr 2027 00:00:00 GMT`
- `Link: </docs/api/versioning-policy.md>; rel="deprecation"`

The routes are marked `deprecated: true` in `/v3/api-docs`. Each one is a handler annotated
`@DeprecatedEndpoint`, which drives both the headers (`DeprecationHeaderInterceptor`) and the spec.
`DeprecatedEndpointsTest` keeps this table, the annotations and the spec in step. Before deprecation,
each route was checked against the SPA (`frontend/src`) and the E2E suite (`e2e/`); none was called.
The announcement starts the 6-month window of §4. Removal is not before the sunset date.

| Route | Deprecated in | Sunset | Use instead |
|---|---|---|---|
| `GET /ImportDetails/GetAll` | 1.2.0 | 2027-04-01 | the ingestion job API (`/api/ingestion/jobs`) |
| `GET /ImportDetails/GetById/{id}` | 1.2.0 | 2027-04-01 | the ingestion job API (`/api/ingestion/jobs`) |
| `POST /ImportDetails/Create` | 1.2.0 | 2027-04-01 | the ingestion job API (`/api/ingestion/jobs`) |
| `PUT /ImportDetails/Update/{id}` | 1.2.0 | 2027-04-01 | the ingestion job API (`/api/ingestion/jobs`) |
| `DELETE /ImportDetails/{id}` | 1.2.0 | 2027-04-01 | the ingestion job API (`/api/ingestion/jobs`) |
| `GET /ProcessStep/GetAll` | 1.2.0 | 2027-04-01 | none (fixed pipeline steps) |
| `GET /ProcessStep/GetById/{id}` | 1.2.0 | 2027-04-01 | none |
| `POST /ProcessStep/Create` | 1.2.0 | 2027-04-01 | none |
| `PUT /ProcessStep/Update` | 1.2.0 | 2027-04-01 | none |
| `DELETE /ProcessStep/{id}` | 1.2.0 | 2027-04-01 | none |
| `GET /TemplateDataTypes/GetById/{id}` | 1.2.0 | 2027-04-01 | `GET /TemplateDataTypes/GetAll` |
| `POST /TemplateDataTypes/Create` | 1.2.0 | 2027-04-01 | none (the data types are fixed) |
| `PUT /TemplateDataTypes/Update` | 1.2.0 | 2027-04-01 | none |
| `GET /ImportHistory/GetById/{id}` | 1.2.0 | 2027-04-01 | `GET /ImportHistory/GetAll` |
| `POST /ImportHistory/Create` | 1.2.0 | 2027-04-01 | `POST /api/ingestion/jobs` (creates the history row) |
| `PUT /ImportHistory/Update/{id}` | 1.2.0 | 2027-04-01 | none |
| `GET /TemplateFields/GetById/{id}` | 1.2.0 | 2027-04-01 | `GET /TemplateFields/GetAll?templateId=` |
| `POST /api/automapper/generate` | 1.2.0 | 2027-04-01 | the pipeline's mapping (`POST /api/ingestion/jobs/{id}/remap`) |
| `POST /api/fileupload/upload` | 1.2.0 | 2027-04-01 | `POST /api/ingestion/jobs` (multipart) |
| `GET /api/extraction/metrics` | 1.2.0 | 2027-04-01 | the admin dashboard (`GET /api/admin/dashboard`) |

**Removed in 1.2.0, without the deprecation window.** These removals use the §3 security
exception and appear in the release notes.

| Route | Why |
|---|---|
| `POST /api/aifiles/send` | It was an open prompt proxy: any `AI_USE` holder could spend the bank's model quota on free text. Nothing in the product called it. |
| `POST /api/transform/transform` | It was the legacy classify-and-extract AI path. The ingestion pipeline replaces it, and no SPA, E2E or integration client called it. The `ratelimit.transform` setting stays in the catalogue, unused, so stored values remain valid. |

**Changed request (1.2.0).** `POST /api/ingestion/jobs/{id}/export/database` no longer accepts a
raw `jdbcUrl`; such a body gets a 400 that names the new fields. The target is now sent as
`vendor` (`POSTGRESQL`, `ORACLE`, `SQLSERVER` or `MARIADB`), `host`, `port` (optional),
`database`, `schema` (optional) and `sslmode` (optional). The server builds the JDBC URL with the
integration hub's `DbDialect`, which applies the connect, login, socket and query timeouts. See
the CHANGELOG migration note.
