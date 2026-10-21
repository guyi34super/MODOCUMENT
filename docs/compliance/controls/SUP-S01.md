# SUP-S01 — Browser session and web-layer hardening

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P1 | Partially Implemented | SUP-S01-E01 | SUP-S01-T01 | R-SUP-S01 |

## Requirement
Close the classic web attack surface: XSS, CSRF, clickjacking and session theft — CSP, HSTS,
secure cookies, CSRF protection, upload validation, header scan grade A. Gate E1 acceptance: a ZAP
active scan shows no High web-layer findings.

## How MoDocument meets it today
- **Security headers, SPA (nginx).** One snippet applied to every static location: CSP
  `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'`
  (no `unsafe-inline`, no `unsafe-eval`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, HSTS `max-age=31536000; includeSubDomains`, `Permissions-Policy`,
  `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy: same-origin`; `server_tokens off`
  (`frontend/security-headers.conf`, `frontend/nginx.conf`).
- **Security headers, API (Spring Security).** CSP `default-src 'none'; frame-ancestors 'none'`,
  `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`,
  HSTS over HTTPS (`src/main/java/com/modocument/api/config/SecurityConfig.java`, pinned by
  `HeaderContractTest`).
- **Header guard in CI.** `scripts/check-nginx-headers.sh` fails the frontend job when a location
  lacks the set or a proxied location stops clearing spoofable forwarding headers
  (`.github/workflows/ci.yml`).
- **CSRF approach (verified).** The API is stateless and Bearer-authenticated; the SPA sets the
  `Authorization` header itself (`frontend/src/api/client.js`); Spring's CSRF filter is disabled
  on purpose and no session cookie exists (`SessionCreationPolicy.STATELESS`). The one cookie,
  `modoc_refresh`, is `HttpOnly; Secure; SameSite=Strict` and path-scoped to `/api/auth/refresh`
  (`src/main/java/com/modocument/api/controller/AuthController.java`). OIDC sign-in uses Spring's
  `state` parameter. CORS is an allow-list (`MODOCUMENT_CORS_ALLOWED_ORIGINS`,
  `src/main/resources/application.yml`).
- **Upload handling.** Extension allow-list (`src/main/java/com/modocument/infrastructure/file/SupportedDocumentTypes.java`);
  content must match the extension by magic bytes, with a NUL scan for text formats
  (`src/main/java/com/modocument/infrastructure/file/UploadContentChecker.java`); 50 MB per file,
  200 MB per request, nginx `client_max_body_size 200m`; server-chosen storage names with no path
  traversal (`src/main/java/com/modocument/infrastructure/file/UploadStorage.java`); document images
  only PNG/JPEG by sniffed bytes, served `inline` with the sniffed type under the API CSP;
  downloads as `attachment`. Design note: `docs/security/web-hardening.md` §4.
- **Clickjacking:** `frame-ancestors 'none'` and `X-Frame-Options: DENY` on both layers.
- **DAST:** weekly ZAP baseline workflow against the compose stack (`.github/workflows/zap-baseline.yml`,
  rules in `.zap/rules.tsv`).

## Evidence SUP-S01-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Header inventory, CSRF posture, cookie flags, upload-handling design note | `docs/security/web-hardening.md` | repo |
| Header check run, 2026-10-19 | `scripts/check-nginx-headers.sh` on `frontend/nginx.conf` (7 locations OK) and `frontend/nginx.mtls-dev.conf` (3 locations OK); `docs/compliance/evidence/A6-test-runs.md` | run |
| Header contract, uploads, CSRF-relevant tests, 2026-10-19 | `docs/compliance/evidence/A6-test-runs.md` (batch 2) | run |
| External header scan grade A (securityheaders.com / Mozilla Observatory) | deployed HTTPS URL | `NOT IN REPO (live cloud)` |
| ZAP scan report | GitHub Actions artifact `zap-baseline-report` | to confirm |

## Test SUP-S01-T01
- **Procedure:** run `scripts/check-nginx-headers.sh` on both nginx configurations; run
  `HeaderContractTest`, `TrustedProxyIntegrationTest`, `UploadContentCheckerTest`,
  `UploadContentGateSec030Test`, `UploadOwnershipEnforcementTest` and `OpenApiContractTest`
  (`everySecuredOperationInTheSpecRefusesAnAnonymousCaller`: no route accepts a request without
  the Bearer header, so a cross-site request carrying only cookies is refused).
- **Expected:** full header set on every location; uploads that lie about their type refused;
  no ambient-credential route; ZAP shows no High finding.
- **Actual (2026-10-19):** header script OK on both files (exit 0); HeaderContractTest 5/5,
  TrustedProxyIntegrationTest 4/4, UploadContentCheckerTest 8/8, UploadContentGateSec030Test 7/7,
  UploadOwnershipEnforcementTest 4/4, OpenApiContractTest 7/7 — all pass. ZAP and the external
  header-grade scan were not run (no deployed URL; GitHub Actions is not starting jobs, see SEC-010).
- **Result:** Partial.
- **Tester:** Agent A6 (automated re-performance); human sign-off required. **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No ZAP result and no external A-grade header scan on file | Config | Restore GitHub Actions runners, run `zap-baseline.yml` and archive the report; scan the first HTTPS deployment with securityheaders.com and Mozilla Observatory | Software Engineering — Bhavish Nobeen | 2026-11-06 (proposed) | Day 30 |
| ZAP job is a passive baseline only; the acceptance names an active scan | Build | Add a ZAP active/API scan against a staging stack with seeded synthetic data | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |
| No dedicated CSRF regression test (a cross-site POST to `/api/auth/refresh` with the cookie) | Build | Add a test that a refresh without the cookie and a request with only cookies are refused, and that the cookie keeps `SameSite=Strict` | Software Engineering — Bhavish Nobeen | 2026-11-06 (proposed) | Day 30 |
| Access token stored in `localStorage` (workbook prefers cookies or memory) | Build | Keep the access token in memory (see SUP-S02) | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |

## Risk R-SUP-S01
Session hijack or script injection in the bank-facing UI. Cause in the workbook: no CSP and
cookies missing flags. Both causes are closed in the repository; what remains unproven is the
deployed result (scan grade, ZAP). Likelihood, impact and residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: external header scan grade (A or better) against the deployed HTTPS URL.
- `TO CONFIRM (organisational)`: ZAP baseline run link and report once GitHub Actions runs jobs again.
