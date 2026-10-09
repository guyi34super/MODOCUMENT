# Key & certificate register — SUP-S08

The single inventory of cryptographic material. One row per key/cert; update
on creation, rotation and retirement. Rotation procedures:
[crypto-standard.md](crypto-standard.md) §3. Review cadence: quarterly
expiry sweep (owner: Bhavish Nobeen). Last updated: 2026-10-09.

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

*Future rows:* staging duplicates of K1–K7 and K15 in the staging account
(own values — SUP-S07; K14 is the same public bundle in both images); the
lock-account bucket KMS key (gate A6).
