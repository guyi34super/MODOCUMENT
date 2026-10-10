# MoDocument infrastructure (Terraform, AWS af-south-1)

Infrastructure-as-code for the bank go-live design decided on **2 Oct 2026**:
AWS **af-south-1** primary, ECS on EC2 (SUP-S04), Multi-AZ RDS PostgreSQL,
CloudFront + WAF edge, immutable backups with an ap-southeast-2 copy, and
GitHub-OIDC deploys with no static cloud keys.

> **Status: written, reviewed, validated, NOT applied.** `terraform fmt
> -check` and `terraform init -backend=false && terraform validate` pass for
> both envs (Terraform 1.9.8, hashicorp/aws 5.100.0, hashicorp/random 3.9.1,
> from a checksum-verified local provider mirror — 9 Oct 2026, go-live
> blocker fixes; re-validated 10 Oct 2026 with the ws24 release changes
> below). `terraform plan/apply` still need AWS credentials and are
> **pending** — run them per env before anything goes live. Nothing here has
> touched any cloud account.

## Layout

```
infra/terraform/
├── modules/
│   ├── network/              VPC, 2 AZs, public/app/db subnets, SGs (db only
│   │                         from app SG — SUP-S06), S3 endpoint, flow logs
│   ├── rds/                  Multi-AZ PostgreSQL 17 (SEC-004), KMS at-rest
│   │                         (SEC-028/C4), rds.force_ssl (SUP-S06), PITR (D4)
│   ├── backups/              AWS Backup vault + VAULT LOCK (D5/PRIV-003),
│   │                         90d retention + ap-southeast-2 copy (D4/PRIV-006),
│   │                         S3 log archive: Object Lock 7y + Glacier (SEC-033)
│   ├── ecs-app/              ECS/EC2 cluster (SUP-S04), task defs with secrets
│   │                         from Secrets Manager (SEC-018), ALB TLS1.2+
│   │                         (SEC-028), target-tracking autoscaling with max
│   │                         cap (SEC-002/003), Session-Manager-only nodes;
│   │                         non-root containers (nginx-unprivileged uid 101
│   │                         on 8080, app uid 10001 on 8081 in the shared
│   │                         awsvpc namespace), all capabilities dropped;
│   │                         deployment circuit breaker with rollback, CI-
│   │                         owned task-definition revisions (ws24)
│   ├── edge/                 CloudFront + AWS WAF managed/rate rules
│   │                         (SUP-S03/SEC-002), origin locked to CloudFront
│   │                         (A12), D3 egress NACL stub
│   ├── security-monitoring/  GuardDuty (PRIV-003), CloudTrail → locked bucket
│   │                         (A6/SEC-015), alarms → SNS (ws18 seam), Session
│   │                         Manager session logging (SEC-015)
│   └── ci-oidc/              GitHub OIDC provider + least-privilege per-env
│                             deploy roles (SUP-S05/A8), trusted per GitHub
│                             environment (ws24)
└── envs/
    ├── production/           af-south-1, full edge, locks gated behind a flag
    └── staging/              SEPARATE account (SUP-S07), scaled down, no locks
```

Every module's `main.tf` header maps it to its Go-Live Gate items and
control ids.

## The two-account model (target state)

| Account | Holds | Why |
|---|---|---|
| **production** | everything in `envs/production` | the workload |
| **staging** | everything in `envs/staging` — own state, own secrets | SUP-S07: test changes never touch production data or IAM |
| **lock account** (to be created — gate A1/A6) | CloudTrail/log-archive bucket, backup vault replica | SEC-015/A6 + PRIV-003/D5: a compromised production admin can neither erase audit trails nor delete recovery points |

Until the lock account exists, the trail and vault copies live in the same
account on Object-Lock/vault-lock storage (still WORM). Moving them is a
**provider-only change**: point `aws.backup_replica` (production
`providers.tf`) and the `trail_bucket*` inputs at the lock account; the
resource graph does not change.

## State backend

`envs/*/backend.tf` holds a commented S3 + DynamoDB backend placeholder.
Create the state bucket (versioned, encrypted, Object Lock recommended) and
the lock table once per account — by hand or a minimal bootstrap stack —
then uncomment and `terraform init -migrate-state`. **Do not apply with
local state.**

## Apply order

Each env is a single root module; `terraform apply` resolves the graph in
one run. Operationally, the sequence that avoids dead ends:

1. **Prereqs (console, once per account):** state bucket + lock table; ACM
   certificates (af-south-1 for the ALB, us-east-1 for CloudFront — register
   both in [`docs/security/key-certificate-register.md`](../../docs/security/key-certificate-register.md));
   the production **ALB certificate must cover `app_domain`** — CloudFront
   forwards the viewer's `Host` and verifies the origin certificate against
   it (edge module origin request policy); root/console MFA (gate A2 —
   organisational, see the README "Implement later" table).
2. **Staging first** (SUP-S07): `cd envs/staging && terraform init &&
   terraform validate && terraform plan && terraform apply`.
3. **Secret values, per env** — every secret the task injects must HAVE a
   value or ECS cannot launch the task (it retries until it can):
   `MODOCUMENT_JWT_SECRET`, `MODOCUMENT_MFA_KEY`, `MODOCUMENT_CONNECTOR_KEY`
   (ws26: the connector SecretVault key, `openssl rand -base64 32`), the
   `modocument_app` DB credentials, and — first deploy only — the **bootstrap admin pair**
   `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` (an empty database
   refuses to boot without it; the password must pass the app's password
   policy). Cleanest: create the shells first, set the values, then apply
   the rest —
   `terraform apply -target=aws_secretsmanager_secret.jwt -target=aws_secretsmanager_secret.mfa_key -target=aws_secretsmanager_secret.connector_key -target=aws_secretsmanager_secret.db_app -target='aws_secretsmanager_secret.bootstrap_admin_email[0]' -target='aws_secretsmanager_secret.bootstrap_admin_password[0]'`,
   then `aws secretsmanager put-secret-value --secret-id <env>/MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD --secret-string file://pw.txt`
   (a file, not the shell history; shred it after) and likewise for the
   others, then the full `terraform apply`. Create the `modocument_app` role
   on RDS following
   [`docker/postgres-init/01-app-role.sh`](../../docker/postgres-init/01-app-role.sh)
   (NOBYPASSRLS — the RLS-subject runtime role, see the rds module header);
   push the released images (ci-oidc outputs the ECR URLs; the tfvars
   `app_image` / `frontend_image` tag must exist before the service is
   created — "Releases and deployments" below); point DNS at the CloudFront
   output.
4. **Production**, same steps, `enforce_backup_locks=false`.
5. **After the first admin exists, per env:** that admin signs in, changes
   the bootstrap password and enrols MFA (mandatory for ADMIN); then set
   `bootstrap_admin_enabled = false` and re-apply. The task definition stops
   injecting the pair, the execution role loses read access to it and both
   secrets are scheduled for deletion (30-day recovery window). The runner
   is a no-op on a non-empty AppUsers table, so this changes nothing at
   runtime — it just removes a standing admin credential. Do **not** delete
   the secrets by hand while the flag is still true: the task definition
   would reference a missing secret and new tasks would fail to launch.
   Re-enable only to seed a rebuilt, empty database.
6. **After the restore drill passes (gate D6):** set
   `enforce_backup_locks=true` in production tfvars and re-apply — vault
   lock + Object Lock COMPLIANCE are **irreversible** past their cooling-off
   windows, which is exactly the point (D5).
7. **After the egress allow-list is signed off (gate D3):** narrow
   `app_egress_https_cidrs`, set `enforce_egress_nacl=true` +
   `egress_allowlist_cidrs`, re-apply.

## Releases and deployments (ws24)

How Terraform and the release pipeline share the ECS service — see
[docs/release/runbook.md](../../docs/release/runbook.md): `release.yml`
(tags) and `deploy.yml` (manual roll-forward/rollback) use the outputs
below as GitHub environment variables (runbook §7).

- **Terraform owns the task-definition template; CI owns its revisions.**
  - Terraform defines the family, roles, environment, secrets, health
    checks and container users/capabilities. It also registers the
    **first** revision, with `app_image` / `frontend_image` from tfvars.
    These must be **released version tags** (`…/app:1.0.0`): the ECR
    repositories are `IMMUTABLE`, so a moving tag like `latest-build`
    cannot be pushed twice.
  - From then on, CI copies the latest revision, pins both images **by
    digest**, registers it and updates the service.
  - `aws_ecs_service` ignores `task_definition` (and `desired_count`, which
    autoscaling owns), so `terraform apply` never rolls a release back to
    the tfvars image.
  - `skip_destroy = true` keeps superseded revisions ACTIVE, so a running
    service or a circuit-breaker rollback never points at a deregistered
    one.
  - A Terraform-only change (a new env var or secret) registers a new
    revision. It reaches the running service with the next CI deploy: run
    `deploy.yml` with the current version.
- **Automatic rollback.** The service has `deployment_circuit_breaker
  { enable = true, rollback = true }`. A deployment whose tasks keep failing
  to start or to pass health checks returns to the last steady revision.
  The schema stays migrated, which is why
  [docs/release/migration-policy.md](../../docs/release/migration-policy.md)
  allows only expand migrations in a release.
- **App environment.** The app container gets:
  - `MODOCUMENT_DEPLOY_ENVIRONMENT` = `staging` / `production`
    (`deploy_environment`, reported by `GET /api/system/version`);
  - `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`: the app refuses to boot
    if a configured default lies outside its hard limits (ws20).
- **CI OIDC trust.** A GitHub job that declares an `environment:` presents
  `sub = repo:<owner>/<repo>:environment:<name>`, not the ref form.
  - **Production** trusts `environment:production`. That environment has
    required reviewers and accepts `v*` tags only; configure both in the
    repository settings together with a tag ruleset protecting `v*`.
  - **Production transition:** production also keeps
    `ref:refs/heads/main` (the pre-ws24 trust). Remove it once one release
    has shipped through the environment-gated pipeline.
  - **Staging** trusts `environment:staging` and keeps its repo-wide
    `repo:<repo>:*` entry.
  - A variable validation pins every subject to one `owner/repo`.
  - The deploy role can also run `ecr:DescribeImages` (tag → digest),
    besides the existing ECS describe/register/update rights.

## Bank integration hub (ws26)

All three switches are **off by default**: an apply without them changes
nothing but the new `MODOCUMENT_CONNECTOR_KEY` secret.

- **`MODOCUMENT_CONNECTOR_KEY`**: a secret shell per env
  (`aws_secretsmanager_secret.connector_key`), injected into the app like
  the MFA key. This is the AES-256-GCM key of the connector SecretVault,
  which seals bank integration credentials at rest. Set its value before
  the next deploy (apply order step 3) and register it in the key register
  (K16).
- **`bank_egress_cidrs`** (network module): for DIRECT-mode connections to
  bank systems reached over a per-bank VPN or PrivateLink (see the root
  README "Implement later"). It adds app-SG egress on 1433, 1521, 3306,
  5432 and 22 to exactly these CIDRs. Empty means no rule. The app also
  requires an approved `BankEgressRule` per destination. In production,
  the egress NACL (`enforce_egress_nacl`) must allow the same ranges.
- **`api_mtls_enabled`** (ecs-app `mtls.tf`): machine clients with client
  certificates use `api_domain` on a second HTTPS listener
  (`api_mtls_listener_port`, default 8443). It runs in **verify** mode
  against a trust store whose CA bundle (`api_mtls_ca_bundle_pem`, the
  banks' issuing CAs) lives in a private, versioned, encrypted S3 bucket.
  Only `api_mtls_client_cidrs` may reach it. Only `Host: api_domain` is
  forwarded, to a dedicated target group on the nginx mTLS server
  (`frontend_mtls_port` 8443). That is the only place that passes the
  `X-Amzn-Mtls-Clientcert-*` headers to the app, and only from the trusted
  ALB. The browser path always strips them. The app also gets
  `MODOCUMENT_MTLS_TRUSTED_PROXY_CIDRS` = `127.0.0.1/32` — its only TCP
  peer is that nginx container on loopback (empty while mTLS is off, so
  the headers are never trusted). Point `api_domain` at the ALB
  (not CloudFront, which cannot carry the client certificate).
  Requires `api_domain`, `api_certificate_arn`, the CA bundle and client
  CIDRs (a precondition fails the plan otherwise).

AGENT-mode connections need none of this. The on-prem Connector Agent dials
out to the normal HTTPS endpoint (`docs/integration/agent.md`).

## What this IaC deliberately does NOT do

- **Set secret values** — containers only (SEC-018); values go in via the
  CLI/console by the key owner and are inventoried in the
  [key & certificate register](../../docs/security/key-certificate-register.md).
- **Create AWS accounts, org SCPs, console MFA** — gates A1/A2 are
  organisational; the README's "Implement later" table assigns owners.
- **Run the restore drill, pen test or load test** — D6/E4/E7 are exercises;
  this code only makes them possible.
- **Serve TLS from the app** — termination is ALB/CloudFront
  (`ELBSecurityPolicy-TLS13-1-2-2021-06`, `TLSv1.2_2021`); the app emits
  HSTS because the frontend nginx forwards the ALB's
  `X-Forwarded-Proto: https` (trusted-proxy mode, below).

## Proxy chain, bootstrap admin, DB CA — resolved go-live blockers

Four defects found when this IaC was integrated with workstreams 16–18
(formerly "Known open items") are fixed in code; what still needs a real
AWS apply or Docker host is listed under each.

1. **Trusted-proxy chain behind ALB/CloudFront.** The frontend image has a
   trusted-proxy mode (`frontend/docker-entrypoint.d/19-modocument-trusted-proxy.envsh`,
   template `frontend/nginx.conf`), switched on by the ecs-app module:
   `MODOCUMENT_TRUSTED_PROXY_CIDRS` = `trusted_proxy_cidrs` (the envs pass
   the VPC CIDR — the ALB is the only VPC-range peer the app SG admits).
   Only a peer in that range may report the client: nginx's realip module
   (`set_real_ip_from` per CIDR, `real_ip_header X-Forwarded-For`,
   `real_ip_recursive on`) takes the rightmost untrusted hop, and the ALB's
   `X-Forwarded-Proto` (`http`/`https` only) is forwarded; from anyone else
   nginx keeps its own `$remote_addr`/`$scheme`. Towards the app it still
   OVERWRITES both headers — one trusted hop, as before.
   **CloudFront decision:** in production the chain is client → CloudFront
   → ALB, and the ALB appends the CloudFront *edge* address, so the
   rightmost untrusted hop is an edge node, not the user. Trusting
   CloudFront's origin-facing ranges in nginx would mean shipping an IP list
   AWS changes at will (the managed prefix list works only in security
   groups). Instead the edge module now uses its own origin request policy
   (all viewer values **plus `Host`**, plus `CloudFront-Viewer-Address`), and
   production sets `client_ip_header = "CloudFront-Viewer-Address"`: nginx
   parses that header (IPv4 and CloudFront's unbracketed IPv6 `addr:port`,
   which realip itself cannot parse) **only** when the peer is the trusted
   ALB and the whole value is one well-formed `address:port` — otherwise it
   falls back to the realip result. Forwarding `Host` also lets CloudFront
   verify the ALB certificate against `app_domain` (prereq above). The app
   itself never sees a client-chosen host: nginx sends `public_host`
   (`MODOCUMENT_PUBLIC_HOST`, set to `app_domain` by both envs) as `Host` and
   `X-Forwarded-Host`, which is where OIDC redirect URIs come from. Staging
   (no CloudFront) uses the `X-Forwarded-For` default: there the ALB is the
   first hop and the rightmost untrusted address is the client. Unset (docker-compose)
   = edge mode: the rendered config is directive-for-directive identical to
   before. Invalid CIDRs/header values stop the container.
   *Pending a real deploy:* confirm in the ALB/app logs that audit IPs are
   viewer addresses (IPv4 and IPv6), that API responses carry HSTS, and
   that the OIDC redirect URI is `https://<app_domain>/…`.
2. **Bootstrap admin.** Secret shells `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL` /
   `_PASSWORD` in both envs (behind `bootstrap_admin_enabled`, default
   `true`), injected via the app container's `secrets`, with the execution
   role's read access extended to exactly those two ARNs. Lifecycle: Apply
   order steps 3 and 5. *Pending:* setting the values, the first sign-in.
3. **`sslmode=verify-full` needs the RDS CA.** The app image now carries
   the AWS global RDS CA bundle at `/app/certs/rds-global-bundle.pem`
   (repo `Dockerfile`, `rds-ca` stage: downloaded at build time, the build
   fails unless it is a PEM bundle of parseable Amazon RDS CA certificates;
   root-owned, read-only), and both envs build `MODOCUMENT_DB_URL` with
   `sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem`.
   Refresh on AWS CA changes: key register K14. *Pending:* the first
   connection to the real RDS endpoint (the image itself was built and
   probed on 9 Oct 2026 — web-hardening.md §5d).
4. **Client-sent `Forwarded` / `X-Forwarded-Port` / `-Prefix` / `-Ssl`
   are cleared in nginx** (found while verifying item 1; it affected compose
   too). Spring's `ForwardedHeaderFilter` prefers RFC 7239 `Forwarded` over
   `X-Forwarded-For`, and CloudFront and the ALB pass a client's `Forwarded`
   header on. Without the fix any client could set the address the app uses
   for per-IP limits and audit rows. The API location of
   `frontend/nginx.conf` now sets all four to `""` (drops them), and
   `scripts/check-nginx-headers.sh` fails CI if that ever regresses.
   Details: [web-hardening.md §5e](../../docs/security/web-hardening.md#5e-proxy-chain-behind-alb--cloudfront-trusted-proxy-mode).
