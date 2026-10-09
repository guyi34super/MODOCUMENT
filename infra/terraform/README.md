# MoDocument infrastructure (Terraform, AWS af-south-1)

Infrastructure-as-code for the bank go-live design decided on **2 Oct 2026**:
AWS **af-south-1** primary, ECS on EC2 (SUP-S04), Multi-AZ RDS PostgreSQL,
CloudFront + WAF edge, immutable backups with an ap-southeast-2 copy, and
GitHub-OIDC deploys with no static cloud keys.

> **Status: written, reviewed, NOT applied.** This container has no
> `terraform` binary, so `terraform init/validate/plan` are **pending a
> machine with terraform installed** — run them (per env) before any apply.
> Structural discipline was checked mechanically (balanced HCL blocks,
> module/variable reference consistency); see workstream 20's verification
> notes. Nothing here has touched any cloud account.

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
│   │                         awsvpc namespace), all capabilities dropped
│   ├── edge/                 CloudFront + AWS WAF managed/rate rules
│   │                         (SUP-S03/SEC-002), origin locked to CloudFront
│   │                         (A12), D3 egress NACL stub
│   ├── security-monitoring/  GuardDuty (PRIV-003), CloudTrail → locked bucket
│   │                         (A6/SEC-015), alarms → SNS (ws18 seam), Session
│   │                         Manager session logging (SEC-015)
│   └── ci-oidc/              GitHub OIDC provider + least-privilege per-env
│                             deploy roles (SUP-S05/A8)
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
   root/console MFA (gate A2 — organisational, see the README "Implement
   later" table).
2. **Staging first** (SUP-S07): `cd envs/staging && terraform init &&
   terraform validate && terraform plan && terraform apply`.
3. **Post-apply, per env:** set the secret VALUES
   (`aws secretsmanager put-secret-value` for `MODOCUMENT_JWT_SECRET`,
   `MODOCUMENT_MFA_KEY`, the `modocument_app` DB credentials); create the
   `modocument_app` role on RDS following
   [`docker/postgres-init/01-app-role.sh`](../../docker/postgres-init/01-app-role.sh)
   (NOBYPASSRLS — the RLS-subject runtime role, see the rds module header);
   push images (ci-oidc outputs the ECR URLs); point DNS at the CloudFront
   output.
4. **Production**, same steps, `enforce_backup_locks=false`.
5. **After the restore drill passes (gate D6):** set
   `enforce_backup_locks=true` in production tfvars and re-apply — vault
   lock + Object Lock COMPLIANCE are **irreversible** past their cooling-off
   windows, which is exactly the point (D5).
6. **After the egress allow-list is signed off (gate D3):** narrow
   `app_egress_https_cidrs`, set `enforce_egress_nacl=true` +
   `egress_allowlist_cidrs`, re-apply.

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
  HSTS when the proxy in front of it forwards `X-Forwarded-Proto: https`
  (see the first open item below).

## Known open items before the first apply

Found when this IaC was integrated with workstreams 16–18; each is a
go-live blocker for the ECS topology, none is solved by this code yet:

1. **Trusted-proxy chain behind ALB/CloudFront.** The frontend nginx
   OVERWRITES `X-Forwarded-For` with its own peer and `X-Forwarded-Proto`
   with its own scheme (the compose contract, `frontend/nginx.conf`). Behind
   the ALB that peer is an ALB node and the scheme is `http`, so per-IP
   throttles (login, MFA, the synthetic probe) and audit IPs would see ALB
   addresses, the app would not emit HSTS, and OIDC redirect URIs would be
   built as `http://`. Fix before go-live: nginx `real_ip` trusting the VPC
   (ALB) and CloudFront origin-facing ranges with `real_ip_recursive on`,
   and `X-Forwarded-Proto: https` for ALB-delivered traffic — as another
   `MODOCUMENT_*` template variable of the frontend image.
2. **Bootstrap admin.** On an empty database the app refuses to start
   without `MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`; the task
   definition does not pass them yet (add a secret shell + `secrets` entry,
   then remove it after the first admin exists).
3. **`sslmode=verify-full` needs the RDS CA.** The env stacks build
   `MODOCUMENT_DB_URL` with `sslmode=verify-full`; pgjdbc then needs the
   Amazon RDS CA bundle (`sslrootcert=...`) inside the app image or a
   mounted path — not shipped yet. `require` works without it but does not
   verify the server certificate.
