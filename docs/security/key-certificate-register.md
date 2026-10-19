# Key & certificate register — SUP-S08

The single inventory of cryptographic material. One row per key/cert; update
on creation, rotation and retirement. Rotation procedures:
[crypto-standard.md](crypto-standard.md) §3. Review cadence: quarterly
expiry sweep (owner: Bhavish Nobeen). Last updated: 2026-10-19 (agent A8,
technology-risk pack: rows K18–K24, AWS-managed keys, expiry alerting,
rotation drill record; previous update 2026-10-09).

**Pre-production:** no production value exists yet. "Location (prod)"
names where the material will live once `infra/terraform/envs/production`
is applied; every created/expiry date is `NOT IN REPO (live cloud)` until
then.

**Rule: this register holds metadata only — never a value, never a
fingerprint of a secret key.**

| Id | Material | Owner | Location (prod) | Algorithm / size | Created | Expiry | Rotation cadence & method |
|---|---|---|---|---|---|---|---|
| K1 | `MODOCUMENT_JWT_SECRET` — access/MFA-challenge token signing | Bhavish Nobeen | AWS Secrets Manager `modocument-prod/MODOCUMENT_JWT_SECRET` (shell created by `infra/terraform/envs/production`); local dev: env var / gitignored secrets file | HMAC-SHA256 key, ≥ 32 bytes (`openssl rand -base64 48`) | *set at first deploy* | none (rotate on cadence) | **180 days** or on suspicion; new value → rolling restart; all sessions re-authenticate (15-min TTL makes this cheap) |
| K2 | `MODOCUMENT_MFA_KEY` — TOTP-secret encryption at rest | Bhavish Nobeen | Secrets Manager `modocument-prod/MODOCUMENT_MFA_KEY` | AES-256-GCM key material, ≥ 32 bytes, SHA-256 domain-separated derivation | *set at first deploy* | none | **Annually** or on suspicion; requires the re-encryption maintenance run (decrypt-old/encrypt-new) — see crypto standard §3 |
| K3 | RDS master (Flyway/schema-owner) password | Bhavish Nobeen | RDS-managed secret (`manage_master_user_password` — `infra/terraform/modules/rds`) | Secrets-Manager-generated | at apply | none | AWS-managed rotation; never in tfvars/state |
| K4 | `modocument_app` runtime DB password (NOBYPASSRLS role) | Bhavish Nobeen | Secrets Manager `modocument-prod/db-app-credentials`; created per `docker/postgres-init/01-app-role.sh` model | Secrets-Manager-generated | *set at role creation* | none | **180 days**: `ALTER ROLE` + secret update + rolling restart |
| K5 | ALB certificate (af-south-1) — must cover the **app domain**: CloudFront forwards the viewer's `Host` and verifies the origin certificate against it (edge module origin request policy) | Taj Aundoo (DNS) / Bhavish Nobeen (infra) | ACM af-south-1, ARN in `terraform.tfvars` | RSA-2048 or EC P-256 (ACM) | *at issue* | ACM-managed | ACM auto-renews (DNS validation must stay in place) |
| K6 | CloudFront viewer certificate (us-east-1) | Taj Aundoo / Bhavish Nobeen | ACM us-east-1, ARN in `terraform.tfvars` | ACM-managed | *at issue* | ACM-managed | ACM auto-renews |
| K7 | RDS storage CMK | Bhavish Nobeen | KMS `alias/modocument-prod-rds` (`modules/rds`) | AES-256 (KMS), rotation enabled | at apply | none | KMS annual auto-rotation |
| K8 | CloudFront origin-verify header value | Bhavish Nobeen | Terraform state (random_password, `modules/edge`) — not a data-plane secret, defense-in-depth only | 32-char random | at apply | none | Rotate by `terraform taint`/replace on suspicion |
| K9 | Keycloak realm / IdP client secrets (when OIDC is enabled) | Bhavish Nobeen | IdP console + Secrets Manager (per `application-oidc.yml.example`) | per IdP | *at enablement* | per IdP | 180 days; coordinate with the bank's IdP admin ([identity-providers.md](identity-providers.md)) |
| K10 | LLM API key (`AIFILES_OPENAI_APIKEY`) | Rushal Seeruthun | Secrets Manager / env; never committed | provider token | *at enablement* | provider | 90 days or on suspicion; app needs restart only (boots without it) |
| K11 | GitHub OIDC deploy roles | Bhavish Nobeen | IAM (`modules/ci-oidc`) — **no static keys exist** (SUP-S05/A8) | STS short-lived, 1h max session | at apply | n/a | Trust-policy review quarterly (subject patterns pinned to repo/branch) |
| K12 | `MODOCUMENT_ALERTS_WEBHOOK_SECRET` — signs security-alert webhook bodies (ws18) | Rushal Seeruthun (receiver side) / Bhavish Nobeen | Secrets Manager or a mounted file (`_FILE`); shared with the alert receiver (Slack/Teams bridge, PagerDuty relay) | HMAC-SHA256 key, ≥ 32 bytes | *at enablement* | none | **180 days** or on suspicion; update sender and receiver together, then restart |
| K13 | `MODOCUMENT_SIEM_WEBHOOK_SECRET` — signs the single-bank SIEM push (ws18) | Rushal Seeruthun / the bank's SIEM owner | Secrets Manager or a mounted file (`_FILE`); shared with that bank's SIEM collector | HMAC-SHA256 key, ≥ 32 bytes | *at enablement* | none | **180 days** or on suspicion; coordinate with the bank's SIEM team |
| K14 | Amazon RDS CA bundle — **trust anchor** (public certificates, not a secret) for the app's `sslmode=verify-full` database connections | Bhavish Nobeen | Baked into the app image at build time: `/app/certs/rds-global-bundle.pem` (root-owned, 0444; repo `Dockerfile`, `rds-ca` stage, from `https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem`); referenced by `sslrootcert=` in the task's `MODOCUMENT_DB_URL` (`infra/terraform/envs/*`) | Root CAs `rds-ca-rsa2048-g1` (the RDS default, the one the instance must use), `rds-ca-rsa4096-g1`, `rds-ca-ecc384-g1`, per region — 111 certificates on 2026-10-09 | per AWS CA | af-south-1 roots: RSA2048 G1 to 2061-05-19, RSA4096/ECC384 G1 to 2121 (the RDS **server** certificates under them are short-lived and auto-rotated by AWS) | **Every image rebuild** re-downloads and re-validates it (build fails on anything but a PEM certificate bundle with Amazon RDS CAs). When AWS announces a CA rotation/new CA: rebuild with `--no-cache --pull`, redeploy, **then** rotate the instance's `ca_cert_identifier` — never the other way round (verify-full would refuse the new server certificate). Quarterly sweep: compare the instance CA with the image's bundle |
| K15 | Bootstrap admin pair `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` — first ADMIN on an empty database | Bhavish Nobeen | Secrets Manager `modocument-prod/MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `…_PASSWORD` (shells created by `infra/terraform/envs/*` while `bootstrap_admin_enabled = true`) | Password per the app's password policy (SEC-021; enforced at boot) | *before the first task starts* | **transient** | One-shot: the runner is a no-op once AppUsers is non-empty. After the first admin signs in, changes the password and enrols MFA: `bootstrap_admin_enabled = false` + apply (task stops injecting it, execution role loses access, both secrets scheduled for deletion). Re-enable only to seed a rebuilt EMPTY database |
| K16 | `MODOCUMENT_CONNECTOR_KEY` — connector SecretVault key: bank integration credentials (DIRECT-mode DB/SFTP/REST secrets) sealed at rest (ws25/26) | Bhavish Nobeen | Secrets Manager `modocument-prod/MODOCUMENT_CONNECTOR_KEY` (shell created by `infra/terraform/envs/*`) | AES-256-GCM, ≥ 32 bytes (`openssl rand -base64 32`), per-bank data key via HKDF(bankId), key version for rotation | *before the first deploy that carries it* | none | **Annually** or on suspicion: set the new key as `MODOCUMENT_CONNECTOR_KEY` with `MODOCUMENT_CONNECTOR_KEY_VERSION` = n+1 and the old one in `MODOCUMENT_CONNECTOR_RETIRED_KEYS` (`n:<base64>`), restart, re-seal, then drop the retired key (`ConnectorSecretVault`). The Terraform task definition does **not** inject the version or retired-keys variables yet — add both before the first rotation. Separate from K2 by design |
| K17 | mTLS trust store — the banks' client-certificate issuing CAs (**public** certificates, a trust anchor, not a secret) for `api.<domain>` (ws26, off by default) | Bhavish Nobeen / each bank's PKI owner | S3 trust-store bucket of `modules/ecs-app/mtls.tf` (`api_mtls_ca_bundle_pem` in tfvars) → ALB trust store | per bank CA | at bank onboarding | per CA | On a bank's CA rollover: add the new CA to the bundle, apply, then remove the old one after its clients moved |
| K18 | File-store CMK — SSE-KMS key of the S3 file store (uploads, exports, doc assets; ws29) | Bhavish Nobeen | KMS `alias/modocument-prod-files` (`infra/terraform/modules/file-store/main.tf`) | AES-256 (KMS), rotation enabled, 30-day deletion window | at apply | none | KMS annual auto-rotation; the task role may use it only through S3 for this bucket |
| K19 | Integration API client secrets (OAuth2 client credentials / API keys issued to bank systems; ws26) | Each bank's integration admin (issuer: maker/checker in the app) / Bhavish Nobeen (platform) | PostgreSQL `ApiClientSecret`, **Argon2id hashes only** (`ApiClientSecretJpaRepository`); the plaintext is shown once to the requester | random, per client | at client creation | per client expiry (`ApiClient`) | On demand via `POST …/clients/{id}/rotate`: the old secret keeps working for an overlap period, then only the new one (`IntegrationApiSecurityTest.rotationKeepsTheOldSecretForTheOverlap`) |
| K20 | Connector Agent credentials (at the bank) — client secret from enrolment, or the agent's own client certificate in MTLS mode | Bank's agent operator / Bhavish Nobeen (registration) | Bank host: `credentials.json` in the agent state volume, or the local secret `agent-client-cert` (`agent/README.md`); MoDocument stores only the hashed secret or the certificate fingerprint | per bank PKI (certificate) or random secret | at enrolment | certificate: per bank PKI; secret: per client | Certificate is fixed at enrolment: rotate by revoking the agent and enrolling again (`docs/integration/agent.md`). Expiry tracking of agent certificates: per bank, `TO CONFIRM (organisational)` |
| K21 | Agent enrolment token (`mda1.…`, single use) | Bank's maker admin | Sealed with K16 for the maker only; consumed on first use | 256 random bits | per enrolment | **15 minutes** | One-shot; nothing to rotate |
| K22 | ECS node and data-plane AWS-managed keys: EBS root volumes (default EBS key), ECR repositories (AES256), log-archive bucket (`aws/s3`), SNS alert topic (`aws/sns`), AWS Backup vaults (AWS Backup default key), Secrets Manager (`aws/secretsmanager`), CloudWatch Logs (service default) | AWS (key) / Bhavish Nobeen (choice) | AWS-managed, per service (`infra/terraform/modules/*`) | AWS-managed | at apply | none | AWS rotates AWS-managed keys automatically. **Note:** the SNS topic's AWS-managed key stops EventBridge and CloudWatch alarms from publishing (DEF-2 in `docs/compliance/evidence/A8-infra-checks.md`); the lock-account archive should use its own CMK (gate A6) |
| K23 | Company e-mail domain DKIM signing keys and DNS records (SPF/DKIM/DMARC) | Taj Aundoo | Mail provider + DNS (not in Terraform) | per provider | `TO CONFIRM (organisational)` | per provider | Per provider; see SUP-S10 |
| K24 | GitHub OIDC provider certificate chain | AWS / GitHub | IAM OIDC provider (`modules/ci-oidc`); thumbprint kept for API compatibility, AWS validates GitHub's chain itself | — | at apply | GitHub-managed | None needed; review with K11 |

**Local/test-only material (not production, listed for completeness):** the
fixed `test`-profile JWT key (deterministic suite), the production-shaped
test keys in `ApiDocsExposureTest`/`HeaderContractTest`/`ProductionBootPgIT`
(throwaway test contexts only; the historical ones are pinned in
`.gitleaksignore`), the Keycloak dev bootstrap admin — all explicitly
non-production per their inline comments. Compose has **no** default DB
passwords or keys since ws16: `POSTGRES_PASSWORD`,
`MODOCUMENT_APP_DB_PASSWORD`, `MODOCUMENT_JWT_SECRET` and
`MODOCUMENT_MFA_KEY` are required and generated per machine into the
gitignored `.env` (README quick start).

*Future rows:* staging duplicates of K1–K7, K15, K16 and K18 in the staging
account (own values — SUP-S07; K14 is the same public bundle in both
images); the lock-account bucket KMS key (gate A6).

## Expiry alerting (SUP-S08 target: alerts at 30 / 14 / 7 days)

| Material | Expires? | Alerting today | Gap |
|---|---|---|---|
| K5, K6 ACM certificates | Yes, ACM renews automatically while DNS validation records stay in place | **None in code.** ACM publishes expiry events (AWS Health / EventBridge) but no Terraform rule routes them to the alert topic (`infra/terraform/modules/security-monitoring/main.tf` has GuardDuty and CloudWatch alarms only) | Add an EventBridge rule for ACM expiry events → the SNS topic; certificates are created by hand (not in Terraform) |
| K14 RDS CA bundle | Roots far in the future; server certificates rotated by AWS | Quarterly manual sweep (this register) | None beyond the sweep |
| K17 bank CAs, K20 agent certificates | Yes, per bank | **None in code** | Record each certificate's expiry here at onboarding; a reminder 30/14/7 days before (tool `TO CONFIRM (organisational)`) |
| K1–K4, K9, K10, K12, K13, K16 (keys and passwords with a rotation cadence, no expiry) | No — rotation is due by cadence | None; the quarterly sweep checks "last rotated" | Add a "last rotated" date to each row once production values exist |
| K19 API client secrets | Per client expiry | The app refuses expired clients (`ApiClientAuthenticator`); no advance warning | Optional: warn the bank's admin before expiry |

## Rotation drill record

| Date | Drill | Kind | Result | Evidence |
|---|---|---|---|---|
| 2026-10-19 | K16 connector key: ciphertexts sealed under version 1 still open after rotating to version 2 with version 1 retired; new seals use version 2; after dropping the retired key, version-1 ciphertexts are refused | Repository test (`ConnectorSecretVaultTest.rotationKeepsOldCiphertextsReadableAndSealsWithTheActiveVersion`) | Pass (13/13 tests in the class) | `docs/compliance/evidence/A8-infra-checks.md` §2 |
| 2026-10-19 | K19 API client secret: old and new secret both work during the overlap; after the overlap only the new one | Repository test (`IntegrationApiSecurityTest.rotationKeepsTheOldSecretForTheOverlap`) | Pass (15/15 tests in the class) | `docs/compliance/evidence/A8-infra-checks.md` §2 |
| — | K1 JWT secret, K2 MFA key, K4 DB password rotated in a deployed environment | Live drill | **Not run** — `NOT IN REPO (live cloud)`; first drill proposed in staging before go-live | — |

There is no repository test for an MFA-key (K2) rotation: the
re-encryption maintenance run in [crypto-standard.md](crypto-standard.md)
§3 is a procedure, not code. `TO CONFIRM (organisational)`: who runs it
and when.
