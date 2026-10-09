# Cryptography standard — SEC-029

Scope: every cryptographic mechanism MoDocument uses, as implemented **in
the code today** (file references are the evidence), plus key management and
the prohibited list. Last reviewed: 2026-10-09 (integration of workstreams 16–20).

## 1. Algorithms in force

| Purpose | Algorithm & parameters | Where (evidence) |
|---|---|---|
| Password hashing | **Argon2id**, Spring Security 5.8 defaults: 16-byte salt, 32-byte hash, parallelism 1, 16 MiB memory, 2 iterations — via `DelegatingPasswordEncoder` with id `argon2`; legacy `{bcrypt}` hashes verify and are transparently re-hashed to Argon2id on the next successful login (`PASSWORD_REHASHED` audit event) | `api/config/SecurityConfig.java` (encoder), `application/usecase/AuthUseCase.java` (upgrade-on-login, constant-cost dummy match for unknown emails) |
| Recovery codes | Same Argon2id password encoder; plaintext shown exactly once at enrolment | `domain/entity/RecoveryCode.java` |
| Access tokens | **JWT HS256** (HMAC-SHA256), key ≥ 32 bytes from `MODOCUMENT_JWT_SECRET`; the app refuses to boot with a missing, short, or known-default key. TTL 15 min (`modocument.security.jwt.ttl-minutes`); per-token `jti` revocation on logout/password change | `infrastructure/security/JwtService.java`, `api/config/SecurityConfig.java` (fail-fast length check), `JwtServiceTest`, `RevocationValidatorTest` |
| MFA challenge tokens | HS256, same key, 5-minute TTL (password-to-second-factor window) | `infrastructure/security/MfaChallengeTokens.java` |
| Refresh tokens | 7-day absolute / 12-h idle rotating family; the presented value is matched by its **SHA-256** hash — the raw token is never stored | `AuthUseCase.java` (rotation), `V15__refresh_and_revoked_tokens.sql` |
| TOTP secrets at rest | **AES-256-GCM** (`AES/GCM/NoPadding`), fresh random 96-bit IV per encryption, 128-bit tag, stored as `base64(iv):base64(ciphertext)`; 256-bit key derived by **SHA-256 with domain separation** (`modocument-mfa-secret-encryption:` prefix) from `MODOCUMENT_MFA_KEY` (≥ 32 bytes, boot-fails otherwise in production) | `infrastructure/security/MfaSecretCipher.java`, `MfaSecretCipherTest` |
| TOTP codes | RFC 6238 **HMAC-SHA1** (the RFC's interoperable default — authenticator-app compatibility; a keyed MAC, not a collision-exposed digest; constant-time comparison via `MessageDigest.isEqual`) | `infrastructure/security/totp/Totp.java`, `TotpTest` |
| Webhook signing (ws18) | **HMAC-SHA256** over the exact request body, sent as `X-MoDocument-Signature: sha256=<hex>` on the security-alert webhook and the single-bank SIEM push when a secret is configured (`MODOCUMENT_ALERTS_WEBHOOK_SECRET`, `MODOCUMENT_SIEM_WEBHOOK_SECRET` — both accept `_FILE`) | `infrastructure/alerting/WebhookAlertChannel.java`, `infrastructure/siem/SiemWebhookForwarder.java`, `AlertingGateD1Test`, `SiemFeedE6Test`; [alerting-and-export-caps.md](alerting-and-export-caps.md) |
| Content hashing (dedup, upload integrity) | **SHA-256** — upload duplicate detection (`UploadedFile.sha256`, V31) and extraction content-hash dedup keys | `domain/model/Hashing.java`, `V31__ws05_uploadedfile_sha256.sql` |
| Transport (edge) | **TLS 1.2 minimum, 1.3 preferred**: ALB listener policy `ELBSecurityPolicy-TLS13-1-2-2021-06`, CloudFront `TLSv1.2_2021`; HTTP→HTTPS redirect; the app emits **HSTS** behind the trusted proxy (`X-Forwarded-Proto: https`) — in the ECS/ALB topology this still needs the proxy-chain fix listed as open item 1 in [`infra/terraform/README.md`](../../infra/terraform/README.md) | `infra/terraform/modules/ecs-app/main.tf`, `infra/terraform/modules/edge/main.tf`, `api/config/SecurityConfig.java`, `frontend/nginx.conf`, `TrustedProxyIntegrationTest` |
| Transport (database) | TLS forced server-side: `rds.force_ssl=1` parameter group 🏗️; client side the app and Flyway share one URL carrying `sslmode` (ws16: `MODOCUMENT_DB_SSLMODE`, compose default `prefer` for local TLS-less PostgreSQL; production `require`/`verify-full` — the task definition uses `verify-full`, which needs the RDS CA bundle, open item 3 in the Terraform README) | `infra/terraform/modules/rds/main.tf`, `infra/terraform/envs/*/main.tf`, `DatabaseTlsConfigTest`, `ProductionBootPgIT`, [web-hardening.md](web-hardening.md) §5d |
| Data at rest (cloud) | RDS storage encryption with a rotating **KMS CMK**; encrypted EBS on ECS nodes; SSE-KMS on the log-archive bucket | `infra/terraform/modules/rds/main.tf`, `modules/ecs-app/main.tf`, `modules/backups/main.tf` |
| IDs | UUIDv7 (time-ordered, non-guessable) for audit events | `domain/model/Uuid7.java`, `Uuid7Test` |

## 2. Planned changes

* **HS256 → RS256/ES256 rotation plan.** HS256 is acceptable while exactly
  one service signs and verifies (single shared secret, never leaves the
  backend). The moment a second verifier appears (separate API gateway,
  sibling service, token introspection by a bank), migrate to an asymmetric
  algorithm: issue RS256 with a KMS-held private key, publish a JWKS
  endpoint, accept both algorithms for one access-token TTL (15 min), then
  drop HS256. Owner: Bhavish Nobeen; trigger: first multi-verifier
  requirement, else revisit at the first annual review (Oct 2027).
* **Key custody → KMS/Secrets Manager.** Today `MODOCUMENT_JWT_SECRET` and
  `MODOCUMENT_MFA_KEY` are environment variables or — preferred since ws16 —
  files via the `<NAME>_FILE` indirection (Docker/Kubernetes secrets;
  `SecretFileEnvironmentPostProcessor`, `SecretFileEnvironmentPostProcessorTest`);
  in the cloud design they are Secrets Manager values injected into the task
  at start (`infra/terraform/modules/ecs-app`, SEC-018), never in images,
  task-def plaintext, tfvars or Terraform state.

## 3. Key management

Inventory, owners, locations, expiry and rotation cadence live in the
[key & certificate register](key-certificate-register.md) (SUP-S08). Rules:

1. **Generation:** ≥ 32 bytes of CSPRNG output (`openssl rand -base64 48`)
   for both HMAC and AES key material; ACM-managed keys for TLS.
2. **Storage:** production — AWS Secrets Manager (containers created by
   Terraform, values set out-of-band by the key owner); local dev — env var
   or the gitignored `application-local-secrets.yml`. **No secret is ever
   committed**; only the `test` profile carries a fixed dev key so the suite
   is deterministic (`application.yml`).
3. **Rotation:** JWT secret rotation invalidates all outstanding tokens
   (users re-authenticate — acceptable: 15-min access TTL). MFA key rotation
   requires re-encrypting stored TOTP secrets (decrypt-with-old /
   encrypt-with-new maintenance run) — schedule in a maintenance window.
   DB passwords: `ALTER ROLE` + Secrets Manager update + rolling restart.
   Cadence and dates: see the register.
4. **Separation:** the MFA key must differ from the JWT secret in any real
   deployment — the dev-profile derivation fallback logs a loud warning and
   is gated behind `permit-dev-paths` (`MfaSecretCipher.java`).

## 4. Prohibited

MD5, SHA-1 as a general-purpose/collision-exposed digest, DES/3DES, RC4,
AES in **ECB** mode, unauthenticated CBC for new designs, TLS < 1.2, and
any homegrown crypto.

*Documented exception:* RFC 6238 TOTP's internal **HMAC-SHA1** (keyed MAC,
interoperability-mandated; see §1).

Enforcement: code review plus the CI Semgrep rules for banned crypto APIs
(ws19, [`.semgrep/modocument.yml`](../../.semgrep/modocument.yml):
`modocument-weak-hash-messagedigest`, `modocument-weak-mac` — with the TOTP
exception scoped to `Totp.java` only — `modocument-weak-cipher`,
`modocument-des-key-spec`), run with `--error` by
`.github/workflows/security.yml` on every push/PR to `main` and weekly.

## 5. Related

* [Security feature catalogue](security-feature-catalogue.md) (SEC-027)
* [Key & certificate register](key-certificate-register.md) (SUP-S08)
* [Threat–control matrix](threat-control-matrix.md) (SEC-040)
* Infrastructure: [`infra/terraform/README.md`](../../infra/terraform/README.md)
