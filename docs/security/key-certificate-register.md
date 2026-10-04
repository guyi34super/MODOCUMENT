# Key & certificate register — SUP-S08

The single inventory of cryptographic material. One row per key/cert; update
on creation, rotation and retirement. Rotation procedures:
[crypto-standard.md](crypto-standard.md) §3. Review cadence: quarterly
expiry sweep (owner: Bhavish Nobeen). Last updated: 2026-10-04.

**Rule: this register holds metadata only — never a value, never a
fingerprint of a secret key.**

| Id | Material | Owner | Location (prod) | Algorithm / size | Created | Expiry | Rotation cadence & method |
|---|---|---|---|---|---|---|---|
| K1 | `MODOCUMENT_JWT_SECRET` — access/MFA-challenge token signing | Bhavish Nobeen | AWS Secrets Manager `modocument-prod/MODOCUMENT_JWT_SECRET` (shell created by `infra/terraform/envs/production`); local dev: env var / gitignored secrets file | HMAC-SHA256 key, ≥ 32 bytes (`openssl rand -base64 48`) | *set at first deploy* | none (rotate on cadence) | **180 days** or on suspicion; new value → rolling restart; all sessions re-authenticate (15-min TTL makes this cheap) |
| K2 | `MODOCUMENT_MFA_KEY` — TOTP-secret encryption at rest | Bhavish Nobeen | Secrets Manager `modocument-prod/MODOCUMENT_MFA_KEY` | AES-256-GCM key material, ≥ 32 bytes, SHA-256 domain-separated derivation | *set at first deploy* | none | **Annually** or on suspicion; requires the re-encryption maintenance run (decrypt-old/encrypt-new) — see crypto standard §3 |
| K3 | RDS master (Flyway/schema-owner) password | Bhavish Nobeen | RDS-managed secret (`manage_master_user_password` — `infra/terraform/modules/rds`) | Secrets-Manager-generated | at apply | none | AWS-managed rotation; never in tfvars/state |
| K4 | `modocument_app` runtime DB password (NOBYPASSRLS role) | Bhavish Nobeen | Secrets Manager `modocument-prod/db-app-credentials`; created per `docker/postgres-init/01-app-role.sh` model | Secrets-Manager-generated | *set at role creation* | none | **180 days**: `ALTER ROLE` + secret update + rolling restart |
| K5 | ALB certificate (af-south-1) | Taj Aundoo (DNS) / Bhavish Nobeen (infra) | ACM af-south-1, ARN in `terraform.tfvars` | RSA-2048 or EC P-256 (ACM) | *at issue* | ACM-managed | ACM auto-renews (DNS validation must stay in place) |
| K6 | CloudFront viewer certificate (us-east-1) | Taj Aundoo / Bhavish Nobeen | ACM us-east-1, ARN in `terraform.tfvars` | ACM-managed | *at issue* | ACM-managed | ACM auto-renews |
| K7 | RDS storage CMK | Bhavish Nobeen | KMS `alias/modocument-prod-rds` (`modules/rds`) | AES-256 (KMS), rotation enabled | at apply | none | KMS annual auto-rotation |
| K8 | CloudFront origin-verify header value | Bhavish Nobeen | Terraform state (random_password, `modules/edge`) — not a data-plane secret, defense-in-depth only | 32-char random | at apply | none | Rotate by `terraform taint`/replace on suspicion |
| K9 | Keycloak realm / IdP client secrets (when OIDC is enabled) | Bhavish Nobeen | IdP console + Secrets Manager (per `application-oidc.yml.example`) | per IdP | *at enablement* | per IdP | 180 days; coordinate with the bank's IdP admin ([identity-providers.md](identity-providers.md)) |
| K10 | LLM API key (`AIFILES_OPENAI_APIKEY`) | Rushal Seeruthun | Secrets Manager / env; never committed | provider token | *at enablement* | provider | 90 days or on suspicion; app needs restart only (boots without it) |
| K11 | GitHub OIDC deploy roles | Bhavish Nobeen | IAM (`modules/ci-oidc`) — **no static keys exist** (SUP-S05/A8) | STS short-lived, 1h max session | at apply | n/a | Trust-policy review quarterly (subject patterns pinned to repo/branch) |

**Local/test-only material (not production, listed for completeness):** the
fixed `test`-profile JWT key (deterministic suite), compose default DB
passwords (`docker-compose.yml`, local only), Keycloak dev bootstrap admin —
all explicitly non-production per their inline comments.

*Future rows:* staging duplicates of K1–K7 in the staging account (own
values — SUP-S07); the lock-account bucket KMS key (gate A6).
