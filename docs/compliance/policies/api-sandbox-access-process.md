# API sandbox access and credentials process

Control **SEC-006** (versioned API catalogue: OpenAPI file, versioning/deprecation policy and
sandbox credentials process). Gate item E2: a new integrator can call the sandbox using only the
documents. Status on 2026-10-19: **process defined, sandbox not yet running.** Items that only the
company can fix are marked `TO CONFIRM (organisational)`.

## 1. What an integrator receives

| Document | Path |
|---|---|
| Machine-readable contract (generated from the code, validated in CI) | `GET /v3/api-docs` on the sandbox host (any signed-in user); route inventory `docs/api/openapi-routes.txt` |
| Versioning and deprecation policy | `docs/api/versioning-policy.md` |
| Integration API v1 developer guide (authentication, idempotency, endpoints, webhook signature check) | `docs/integration/developer-guide.md` |
| Postman collection | `docs/integration/modocument-integration-v1.postman_collection.json` |
| Connector Agent guide (on-premises agent) | `docs/integration/agent.md` |

## 2. Sandbox environment

- **Where:** a non-production deployment. The repository defines a `staging` environment as
  Terraform (`infra/terraform/envs/staging/main.tf`); whether the sandbox is that environment or a
  separate one is `TO CONFIRM (organisational)`. Nothing is deployed yet (`NOT IN REPO (live cloud)`).
- **Data:** synthetic data only, never a copy of production or bank data (the same rule as the
  load tests, `loadtest/README.md`).
- **Same controls as production:** same rate limits (`429` with `Retry-After`), same permission
  model, same token lifetimes, so integrators learn the production behaviour.
- **Local alternative for bank IT developers:** the bank simulator (`e2e/bank-simulator/README.md`)
  and the local mutual-TLS listener (`docker-compose.mtls-dev.yml`, `scripts/dev-mtls-certs.sh`;
  developer guide §8).

## 3. Requesting credentials

| Step | Who | How | Proof in the code |
|---|---|---|---|
| 1. Request | The integrating bank's project lead | Ticket to the MoDocument owner naming the bank, the system, the scopes needed (`INTEGRATION_INGEST`, `INTEGRATION_RESULTS`, `INTEGRATION_DOCS`, `INTEGRATION_EVENTS`), the auth method (OAuth2 client credentials, API key or mutual TLS) and the source IP ranges | Ticketing tool `TO CONFIRM (organisational)` |
| 2. Create the client (maker) | A bank administrator in the sandbox bank | `POST /api/admin/integrations/api-clients` (also in the admin UI), which submits an `API_CLIENT_CREATE` pending change | `src/main/java/com/modocument/application/usecase/ApiClientProvisioning.java` |
| 3. Approve (checker) | A second administrator | Approves the pending change; the secret is generated and hashed (Argon2id) | `src/main/java/com/modocument/application/usecase/ApiClientCreateChangeApplier.java` |
| 4. Hand-over | The maker | Reads the initial secret **once** within 24 h (`GET /api/admin/integrations/api-clients/{id}/initial-secret`) and passes it to the integrator through the agreed secure channel `TO CONFIRM (organisational)` | `src/main/resources/db/migration/V260__ws26_machine_identity.sql` |
| 5. First call | The integrator | `POST /api/integration/oauth/token`, then `GET /api/integration/v1/templates` (developer guide §2a, §4) | `src/main/java/com/modocument/api/config/SecurityConfig.java` |
| 6. Expiry and revocation | The bank administrator | Sandbox clients get an expiry date (default 365 days; recommended 90 days for the sandbox); revoke when the project ends | `src/main/java/com/modocument/application/usecase/ApiClientAdminUseCase.java` |

User-level API access (for testers who call the user API with a bearer token) is an admin-created
user (`READ_ONLY` or `READ_WRITE`) through the JML runbook (`docs/security/jml-runbook.md`). No
shared accounts.

## 4. Open items (owner and date to be set)

| Item | Owner | Date |
|---|---|---|
| Choose and deploy the sandbox environment; load the synthetic data set | Software Engineering — Bhavish Nobeen | `TO CONFIRM (organisational)` |
| Name the ticket queue and the secure channel for initial secrets | Software Engineering — Bhavish Nobeen | `TO CONFIRM (organisational)` |
| Data-refresh schedule for the sandbox | Software Engineering — Bhavish Nobeen | `TO CONFIRM (organisational)` |
| Run the E2 acceptance: a new integrator calls the sandbox using only these documents | Software Engineering — Bhavish Nobeen | after deployment |
