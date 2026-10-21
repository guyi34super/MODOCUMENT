# Cryptography standard — SEC-029

Scope: every cryptographic mechanism MoDocument uses, as implemented **in
the code and Terraform today** (file references are the evidence), the key
management design, and the prohibited list. Last reviewed: **2026-10-19**
against `main` at `23647fe` (phase 2, v1.2.0 and the integration hub), for
the SEC-028/SEC-029 evidence pack (`docs/compliance/controls/SEC-029.md`).

Owner: Software Engineering — Bhavish Nobeen. Approver: Reezvi Pydiah
(security documents; proposed — confirm). Review: yearly, and whenever an
algorithm, key or TLS policy changes.

🏗️ = written as Terraform, not applied (`infra/terraform/README.md`).

## 1. Standard (what every new design must use)

| Purpose | Required | Minimum |
|---|---|---|
| Symmetric encryption of data and secrets | AES-256-GCM, random 96-bit nonce, 128-bit tag, associated data where the ciphertext is bound to a tenant | AES-256-GCM only |
| Key derivation from a master key | HKDF-SHA256 (RFC 5869) with a domain-separating `info` | — |
| Password and client-secret hashing | Argon2id | BCrypt accepted only for verifying legacy hashes |
| Message authentication / signatures | HMAC-SHA256; JWT HS256 while one service signs and verifies (§3) | — |
| Digests (ids, dedup, token lookup) | SHA-256 | — |
| Transport | TLS 1.3 preferred, TLS 1.2 minimum, forward-secret suites only (AWS policies in §4) | TLS 1.2 |
| Data at rest in AWS | KMS customer-managed key with yearly rotation for stores that hold customer data | SSE with an AWS-managed key for logs and metadata |
| Randomness | `java.security.SecureRandom`; `openssl rand` for operator-generated keys | — |

## 2. Algorithms in force (application)

| Purpose | Algorithm and parameters | Where (evidence) |
|---|---|---|
| Password hashing | **Argon2id** (Spring Security defaults: 16-byte salt, 32-byte hash, parallelism 1, 16 MiB memory, 2 iterations) via `DelegatingPasswordEncoder`; legacy `{bcrypt}` hashes verify and are re-hashed to Argon2id at the next login | `src/main/java/com/modocument/api/config/SecurityConfig.java`, `src/main/java/com/modocument/application/usecase/AuthUseCase.java` |
| Recovery codes | Argon2id; plaintext shown once | `src/main/java/com/modocument/domain/entity/RecoveryCode.java` |
| API client secrets (ws26) | **Argon2id** hashes in `ApiClientSecret`; API keys are `<prefix>.<secret>`, looked up by prefix; a short-lived cache of a verified key's SHA-256 avoids repeated Argon2id work | `src/main/resources/db/migration/V260__ws26_machine_identity.sql`, `src/main/java/com/modocument/infrastructure/security/client/ApiClientAuthenticator.java` |
| User access tokens | **JWT HS256**, key ≥ 32 bytes from `MODOCUMENT_JWT_SECRET`; boot refused with a missing, short or known-default key; TTL 15 min; per-token `jti` revocation | `src/main/java/com/modocument/infrastructure/security/JwtService.java`, `JwtServiceTest`, `RevocationValidatorTest` |
| Client (machine) tokens (ws26) | JWT HS256 with the same key, `typ=client` and no roles claim, so user and client tokens cannot stand in for each other; TTL `integration.clientTokenTtl` (5–15 min) | `src/main/java/com/modocument/infrastructure/security/client/ClientTokenService.java` |
| MFA challenge tokens | HS256, same key, 5-minute TTL | `src/main/java/com/modocument/infrastructure/security/MfaChallengeTokens.java` |
| Refresh tokens | 7-day absolute / 12-h idle rotating family, matched by **SHA-256** hash; the raw token is never stored | `src/main/java/com/modocument/application/usecase/AuthUseCase.java`, `src/main/resources/db/migration/V15__refresh_and_revoked_tokens.sql` |
| TOTP secrets at rest | **AES-256-GCM** (shared primitive `AesGcm`), stored as `base64(iv):base64(ciphertext)`; 256-bit key = SHA-256(`modocument-mfa-secret-encryption:` + `MODOCUMENT_MFA_KEY`), key ≥ 32 bytes, boot refused otherwise in production | `src/main/java/com/modocument/infrastructure/security/MfaSecretCipher.java`, `src/main/java/com/modocument/infrastructure/security/AesGcm.java`, `MfaSecretCipherTest` |
| Connection secrets — SecretVault (ws25) | **AES-256-GCM** under `MODOCUMENT_CONNECTOR_KEY` (exactly 32 bytes, base64); **per-bank key = HKDF-SHA256(master, info `modocument-connector:<bankId>`)**; associated data `modocument-connector:<bankId>:v<keyVersion>`, so a ciphertext cannot be opened for another bank or key version; stored as `base64(nonce‖ciphertext‖tag)` with its key version; retired keys stay readable for rotation; staging/production refuse to boot without the key | `src/main/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVault.java`, `ConnectorSecretVaultTest`; [secrets.md](../integration/secrets.md) |
| Agent enrolment token (ws26) | 256 random bits, single use, 15 minutes; stored as **SHA-256** (hex), compared in constant time | `src/main/java/com/modocument/application/usecase/AgentEnrolmentToken.java`, `src/main/resources/db/migration/V261__ws26_integration_agents.sql`, `AgentEnrolmentTokenTest` |
| mTLS client identity (ws26) | SHA-256 fingerprint of the client certificate (DER) bound to the API client (RFC 8705) | `src/main/java/com/modocument/infrastructure/security/client/MtlsClientCertificates.java`, `RestApiMtlsTest`, `AgentMtlsTest` |
| Idempotency keys (ws26) | SHA-256 of the request body | `src/main/java/com/modocument/infrastructure/integration/IdempotencyStore.java` |
| TOTP codes | RFC 6238 **HMAC-SHA1** (interoperability; a keyed MAC; constant-time comparison) | `src/main/java/com/modocument/infrastructure/security/totp/Totp.java`, `TotpTest` |
| Webhook signing | **HMAC-SHA256** over the exact body: security-alert webhook and SIEM push (`X-MoDocument-Signature: sha256=<hex>`), and outbound integration webhooks (`hmacSecret` in the SecretVault) | `src/main/java/com/modocument/infrastructure/alerting/WebhookAlertChannel.java`, `connector-core/src/main/java/com/modocument/connector/core/webhook/WebhookSigner.java`, `WebhookIntegrationConnectorTest` |
| Content hashing | **SHA-256** — upload integrity and dedup, extraction dedup, AI result-cache keys | `src/main/java/com/modocument/domain/model/Hashing.java`, `src/main/java/com/modocument/infrastructure/ai/AiResultCache.java` |
| SFTP | Server host key pinned by its SHA-256 fingerprint (`hostKeyFingerprint`); connection refused on mismatch | `connector-core/src/main/java/com/modocument/connector/core/sftp/SftpConnector.java`, `SftpConnectorTest` |
| IDs | UUIDv7 for audit events and request ids | `src/main/java/com/modocument/domain/model/Uuid7.java` |

## 3. Planned changes

* **HS256 → RS256/ES256.** HS256 is acceptable while exactly one service
  signs and verifies. When a second verifier appears, move to an asymmetric
  algorithm with a KMS-held private key and a JWKS endpoint, accept both for
  one access-token TTL, then drop HS256. Owner: Bhavish Nobeen; trigger: the
  first multi-verifier requirement, else the October 2027 review.
* **Own CMKs for backups and logs** (gap found 2026-10-19, §4.2): give the
  AWS Backup vaults, the log-archive bucket and the CloudWatch log groups
  their own KMS keys with explicit key policies. Owner and date:
  `docs/compliance/controls/SEC-028.md`.

## 4. Cloud encryption and TLS (Terraform) 🏗️

### 4.1 TLS policies

| Hop | Setting | Value | Where |
|---|---|---|---|
| Browser → CloudFront (production) | `minimum_protocol_version` | `TLSv1.2_2021` | `infra/terraform/modules/edge/main.tf` |
| CloudFront → ALB origin | `origin_protocol_policy`, `origin_ssl_protocols` | `https-only`, `TLSv1.2` | `infra/terraform/modules/edge/main.tf` |
| Client → ALB HTTPS listener (both envs) | `ssl_policy` | `ELBSecurityPolicy-TLS13-1-2-2021-06` (TLS 1.3 and 1.2) | `infra/terraform/modules/ecs-app/main.tf` |
| Machine client → ALB mTLS listener | `ssl_policy`, trust store | `ELBSecurityPolicy-TLS13-1-2-2021-06`, CA bundle in S3 | `infra/terraform/modules/ecs-app/mtls.tf` |
| HTTP :80 | redirect | 301 to HTTPS :443 | `infra/terraform/modules/ecs-app/main.tf` |
| App → RDS PostgreSQL | server `rds.force_ssl=1`; client `sslmode=verify-full` with the RDS CA bundle baked into the image | `infra/terraform/modules/rds/main.tf`, `infra/terraform/envs/production/main.tf`, `Dockerfile`, `DatabaseTlsConfigTest` |
| App → S3 file store | bucket policy denies requests without `aws:SecureTransport` | `infra/terraform/modules/file-store/main.tf` |
| HSTS | emitted by the app behind the trusted proxy | `src/main/java/com/modocument/api/config/SecurityConfig.java`, `TrustedProxyIntegrationTest` |

Not encrypted today: **ALB → ECS task** (target group protocol `HTTP` on
8080, inside private subnets) and nginx → app inside the task. Gap on
`docs/compliance/controls/SEC-028.md`.

### 4.2 Keys and key policies

| Store | Key | Rotation | Key policy | Where |
|---|---|---|---|---|
| RDS storage, automated backups, snapshots | CMK `alias/<name>-rds` | yearly (`enable_key_rotation = true`), 30-day deletion window | **No explicit policy** — the AWS default key policy (account root, delegated to IAM) applies | `infra/terraform/modules/rds/main.tf` |
| S3 file store (uploads, exports, document assets) | CMK `alias/<name>-files`; bucket default SSE-KMS **and** a bucket policy that denies PUTs without SSE-KMS or with another key; the app names the key on every PUT | yearly, 30-day deletion window | **No explicit policy** (default); the task role may use it only via S3 for this bucket (`kms:ViaService`, encryption context) | `infra/terraform/modules/file-store/main.tf`, `infra/terraform/modules/ecs-app/main.tf`, `src/main/java/com/modocument/infrastructure/file/store/S3FileStore.java` |
| AWS Backup vaults (primary and ap-southeast-2 replica) | none set → AWS Backup default key | AWS-managed | AWS-managed | `infra/terraform/modules/backups/main.tf` |
| Log-archive bucket (7-year WORM) | SSE-KMS with the AWS-managed `aws/s3` key (no `kms_master_key_id`) | AWS-managed | AWS-managed | `infra/terraform/modules/backups/main.tf` |
| Secrets Manager (JWT, MFA, connector key, DB credentials, bootstrap) | no `kms_key_id` → AWS-managed `aws/secretsmanager` | AWS-managed | AWS-managed | `infra/terraform/envs/production/main.tf` |
| ECR repositories | `AES256` (S3-managed) | — | — | `infra/terraform/modules/ci-oidc/main.tf` |
| mTLS trust-store bucket | `AES256` | — | — | `infra/terraform/modules/ecs-app/mtls.tf` |
| SNS alarm topic | `alias/aws/sns` | AWS-managed | AWS-managed | `infra/terraform/modules/security-monitoring/main.tf` |
| ECS node EBS volumes | `encrypted = true` (default EBS key) | AWS-managed | AWS-managed | `infra/terraform/modules/ecs-app/main.tf` |
| CloudWatch log groups | no `kms_key_id` → CloudWatch default encryption | AWS-managed | AWS-managed | `infra/terraform/modules/ecs-app/main.tf`, `infra/terraform/modules/network/main.tf` |

## 5. Key management

The inventory, owners, locations, expiry and rotation dates are in the
[key & certificate register](key-certificate-register.md) (SUP-S08). Rules:

1. **Generation:** at least 32 bytes of CSPRNG output (`openssl rand -base64 48`;
   exactly 32 bytes for `MODOCUMENT_CONNECTOR_KEY`: `openssl rand -base64 32`);
   ACM-managed certificates for TLS.
2. **Storage:** production — AWS Secrets Manager, injected into the task at
   start (`infra/terraform/modules/ecs-app/main.tf`); never in images, task
   definition plaintext, tfvars or Terraform state. Local — env var or a file
   via `<NAME>_FILE`. **No secret is committed**; only the `test` profile
   carries fixed keys so the suite is deterministic. gitleaks scans the full
   history (`.github/workflows/security.yml`).
3. **Separation:** the JWT secret, the MFA key and the connector key are three
   different secrets (three Secrets Manager entries). Each bank gets its own
   derived connector key.
4. **Rotation:** JWT secret — all sessions end (15-min TTL makes this
   acceptable). MFA key — decrypt-with-old / encrypt-with-new maintenance run.
   Connector key — new active version, old one listed as retired until every
   secret is re-sealed ([secrets.md](../integration/secrets.md)). KMS CMKs —
   automatic yearly rotation. DB passwords — managed by RDS/Secrets Manager.
5. **Destruction:** KMS keys have a 30-day deletion window; a scheduled deletion
   needs the approver's written OK (`TO CONFIRM (organisational)`).

## 6. Prohibited

MD5, SHA-1 as a general digest, DES/3DES, RC4, AES-ECB, unauthenticated CBC
for new designs, TLS below 1.2, RSA keys below 2048 bits, and any homegrown
cryptography.

*Documented exception:* RFC 6238 TOTP's internal HMAC-SHA1 (§2).

Enforcement: code review plus Semgrep rules `modocument-weak-hash-messagedigest`,
`modocument-weak-mac` (TOTP exception scoped to `Totp.java`),
`modocument-weak-cipher` and `modocument-des-key-spec`
(`.semgrep/modocument.yml`), run with `--error` by
`.github/workflows/security.yml`. Run R6 on 2026-10-19: 0 findings on 1,033
files (`docs/compliance/evidence/A7-runs.md`). Note: no workflow job has run
since 2026-10-16 (CI outage, SEC-025).

## 7. Related

* [Security feature catalogue](security-feature-catalogue.md) (SEC-027)
* [Key & certificate register](key-certificate-register.md) (SUP-S08)
* Encryption inventory per store and hop: `docs/compliance/controls/SEC-028.md`
* Infrastructure: [`infra/terraform/README.md`](../../infra/terraform/README.md)
