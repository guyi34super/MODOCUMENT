# A8 — Infrastructure checks and Terraform fact register

Agent A8 (IT Infrastructure & Operations controls), run on 2026-10-19.
This file holds (1) the Terraform format and validation run made for this
pack, (2) the test run made for this pack, (3) every Terraform fact the A8
control pages cite, with its file and resource, and (4) the defects found.

Nothing in `infra/terraform/` has been applied to AWS. Every fact below is a
fact about the code. What the live account does is `NOT IN REPO (live cloud)`.

## 1. Terraform format and validation run

| Item | Value |
|---|---|
| Date and time | 2026-10-19T16:25:07Z |
| Terraform | v1.9.8 linux_amd64, downloaded from releases.hashicorp.com, SHA-256 checked against the published `SHA256SUMS` file (the GPG signature of that file was not checked) |
| Providers | hashicorp/aws v5.100.0 and hashicorp/random v3.9.1, from a local file-system mirror built from releases.hashicorp.com with SHA-256 checked against each `SHA256SUMS` file. `registry.terraform.io` is blocked by the session proxy (HTTP 403), so `terraform init` reports the providers as "unauthenticated" (no registry signature). |
| Working copy | a copy of `infra/terraform` in the agent scratchpad, so no `.terraform/` folder or lock file enters the repository |
| Commands | `terraform fmt -check -recursive` (from `infra/terraform`), then per env `terraform init -backend=false -input=false` and `terraform validate` |

| Check | Scope | Exit code | Output |
|---|---|---|---|
| `terraform fmt -check -recursive` | all of `infra/terraform` | 0 | no file listed (all formatted) |
| `terraform init -backend=false` | `infra/terraform/envs/staging` | 0 | aws v5.100.0, random v3.9.1 installed |
| `terraform validate` | `infra/terraform/envs/staging` | 0 | "Success! The configuration is valid." |
| `terraform init -backend=false` | `infra/terraform/envs/production` | 0 | aws v5.100.0, random v3.9.1 installed |
| `terraform validate` | `infra/terraform/envs/production` | 0 | "Success! The configuration is valid." |

`terraform plan` and `terraform apply` were **not run**: they need AWS
credentials and accounts, which do not exist yet (`NOT IN REPO (live cloud)`).
`validate` checks syntax, types and references only. It does not catch the
runtime defects in section 4.

No CI workflow runs `terraform fmt`, `validate` or an IaC scanner (tfsec,
Checkov). Checked: `.github/workflows/ci.yml`, `.github/workflows/security.yml`,
`.github/workflows/release.yml`.

## 2. Test run made for this pack

| Item | Value |
|---|---|
| Command | `mvn -o -B test -Dtest='ConnectorSecretVaultTest,SyntheticProbeTest,IntegrationApiSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` (repository root, offline Maven, Java 21.0.11) |
| Finished | 2026-10-19T16:32:10Z, total time 03:49 min, `BUILD SUCCESS` |

| Test class | Tests | Failures | Errors | Skipped | Used by |
|---|---|---|---|---|---|
| `src/test/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVaultTest.java` | 13 | 0 | 0 | 0 | SUP-S08 rotation drill (method `rotationKeepsOldCiphertextsReadableAndSealsWithTheActiveVersion`) |
| `src/test/java/com/modocument/api/integration/IntegrationApiSecurityTest.java` | 15 | 0 | 0 | 0 | SUP-S08 (method `rotationKeepsTheOldSecretForTheOverlap`: API-client secret rotation) |
| `src/test/java/com/modocument/api/SyntheticProbeTest.java` | 3 | 0 | 0 | 0 | SEC-005, SUP-S11 (public synthetic probe) |
| **Total** | **31** | **0** | **0** | **0** | |

One `ERROR` log line during `SyntheticProbeTest` comes from the SIEM push
forwarder reporting that a test bank code names no bank; it is a log
message, not a test failure.

## 3. Terraform facts cited by the A8 pages

Each row is a fact read from the code on 2026-10-19. IDs (TF-nn) are used by
the control pages and policies.

### Environments and providers

| ID | Fact | File | Resource / setting |
|---|---|---|---|
| TF-01 | Production primary region is `af-south-1` (Cape Town). | `infra/terraform/envs/production/providers.tf` | `provider "aws"` `region = "af-south-1"` |
| TF-02 | CloudFront certificate and the CLOUDFRONT-scope WAF use `us-east-1`. | `infra/terraform/envs/production/providers.tf` | `provider "aws"` alias `us_east_1` |
| TF-03 | Backup copies go to `ap-southeast-2` (Sydney) through the `backup_replica` provider. It points at the same account until a separate lock account exists. | `infra/terraform/envs/production/providers.tf` | `provider "aws"` alias `backup_replica` |
| TF-04 | Staging is a separate root module meant for a separate AWS account, also in `af-south-1`, with its own `backup_replica` provider in `ap-southeast-2`. | `infra/terraform/envs/staging/providers.tf` | `provider "aws"` |
| TF-05 | State backend is a commented placeholder (S3 + DynamoDB). Nothing may be applied with local state. | `infra/terraform/envs/production/backend.tf` | commented `backend "s3"` |
| TF-06 | Production wires eight modules: network, rds, file_store, backups, ecs_app, edge, security_monitoring, ci_oidc. | `infra/terraform/envs/production/main.tf` | `module` blocks |
| TF-07 | Staging has no edge module (no CloudFront, no WAF). Its ALB takes HTTPS from `var.ingress_cidrs`. | `infra/terraform/envs/staging/main.tf` | `aws_security_group_rule.alb_https_in` |
| TF-08 | Production secret containers: `MODOCUMENT_JWT_SECRET`, `MODOCUMENT_MFA_KEY`, `MODOCUMENT_CONNECTOR_KEY`, `db-app-credentials`, and the bootstrap and platform admin pairs behind flags. Values are set outside Terraform. | `infra/terraform/envs/production/main.tf` | `aws_secretsmanager_secret.*` |

### Network (`infra/terraform/modules/network/main.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-10 | One VPC; two AZs (`slice(data.aws_availability_zones.available.names, 0, 2)`); three subnet tiers per AZ: public, private-app, private-db. Public subnets do not map public IPs. | `aws_vpc.this`, `aws_subnet.public`, `aws_subnet.private_app`, `aws_subnet.private_db` |
| TF-11 | VPC CIDR default `10.40.0.0/16` (production); staging `10.41.0.0/16`. | `variable "vpc_cidr"` (module and `infra/terraform/envs/staging/variables.tf`) |
| TF-12 | NAT gateways: `var.nat_gateway_count`, production 2 (one per AZ), staging 1. | `aws_nat_gateway.this`; `nat_gateway_count` in both env `main.tf` files |
| TF-13 | DB subnets have no internet route (no IGW, no NAT). | `aws_route_table.private_db` |
| TF-14 | DB security group: ingress 5432 from the app SG only; no egress rules. | `aws_security_group_rule.db_from_app` |
| TF-15 | App security group: ingress only from the ALB SG on the frontend port (8080). | `aws_security_group_rule.app_from_alb` |
| TF-16 | App HTTPS egress goes to `var.app_egress_https_cidrs`, default `0.0.0.0/0` (the gate D3 seam, not yet narrowed). | `aws_security_group_rule.app_egress_https` |
| TF-17 | S3 gateway endpoint on the app and DB route tables. Interface endpoints (ssm, ssmmessages, ec2messages, secretsmanager, logs, ecr.api, ecr.dkr) behind `enable_interface_endpoints`; production sets it `true`, and the endpoints use the **app** security group. | `aws_vpc_endpoint.s3`, `aws_vpc_endpoint.interface` |
| TF-18 | VPC flow logs (ALL traffic, 60 s aggregation) to CloudWatch, retention `var.flow_log_retention_days` = 30 in both envs. The log group names no KMS key. | `aws_flow_log.this`, `aws_cloudwatch_log_group.flow_logs` |
| TF-19 | Bank DIRECT-mode egress (1433, 1521, 3306, 5432, 22) exists only when `bank_egress_cidrs` is set; default empty. | `aws_security_group_rule.app_egress_bank` |

### Database (`infra/terraform/modules/rds/main.tf`, `variables.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-20 | PostgreSQL `engine_version` default `17.4`; production class `db.m6g.large`, staging `db.t4g.medium`. | `aws_db_instance.this`; `db_instance_class` in both env `variables.tf` |
| TF-21 | `multi_az = var.multi_az`: production `true`, staging `var.db_multi_az` default `false`. | `aws_db_instance.this`; `module "rds"` in both envs |
| TF-22 | Storage encrypted with a dedicated KMS key, key rotation on, 30-day deletion window, alias `alias/<name>-rds`. | `aws_kms_key.db`, `aws_kms_alias.db` |
| TF-23 | Parameter group (`postgres17`): `rds.force_ssl = 1`, `log_statement = ddl`, `log_connections = 1`, `log_disconnections = 1`. | `aws_db_parameter_group.this` |
| TF-24 | `publicly_accessible = false`, port 5432, DB subnet group = private DB subnets, SG = DB SG. | `aws_db_instance.this`, `aws_db_subnet_group.this` |
| TF-25 | Automated backups / PITR: `backup_retention_period = var.backup_retention_days` default 35 days; `backup_window = "01:00-02:00"` UTC; `delete_automated_backups = false`; `skip_final_snapshot = false`; final snapshot `<name>-final`. | `aws_db_instance.this` |
| TF-26 | `maintenance_window = "sun:03:00-sun:04:00"` UTC; `auto_minor_version_upgrade = true`; `apply_immediately = false`. | `aws_db_instance.this` |
| TF-27 | `deletion_protection`: production `true`, staging `false`. | `module "rds"` in both envs |
| TF-28 | Master password managed by RDS in Secrets Manager (`manage_master_user_password = true`). | `aws_db_instance.this` |
| TF-29 | CloudWatch log exports `postgresql` and `upgrade`. Terraform declares no log group for them, so their retention and encryption are not set in code. | `enabled_cloudwatch_logs_exports` |
| TF-30 | Performance Insights off by design (it samples live SQL that may contain PII). | `performance_insights_enabled = false` |
| TF-31 | The instance carries the tag `Backup = modocument`, which the backups module selects. | `tags` on `aws_db_instance.this` |

### Backups and log archive (`infra/terraform/modules/backups/main.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-40 | AWS Backup vault in the primary region and a replica vault through `aws.replica` (ap-southeast-2). `force_destroy = false`. No `kms_key_arn` is set, so AWS Backup uses its default key. | `aws_backup_vault.primary`, `aws_backup_vault.replica` |
| TF-41 | Vault lock (compliance mode once `changeable_for_days = 3` passes), `min_retention_days = 90`, `max_retention_days = 366`, on both vaults — only when `enforce_locks = true`. Production passes `var.enforce_backup_locks` (default `false`); staging hard-codes `false`. | `aws_backup_vault_lock_configuration.primary`, `.replica` |
| TF-42 | Backup plan: daily at `cron(30 0 * * ? *)` (00:30 UTC), keep 90 days, copy to the replica vault, keep 90 days. No continuous (PITR) backup rule. | `aws_backup_plan.this` rule `daily-90d-crossregion` |
| TF-43 | Selection: every resource tagged `Backup = modocument` (today only the RDS instance). | `aws_backup_selection.tagged` |
| TF-44 | Log-archive bucket `<name>-log-archive-<account>`: Object Lock enabled at creation, versioning on, all public access blocked, SSE `aws:kms` with no key named (AWS managed key). | `aws_s3_bucket.log_archive`, `aws_s3_bucket_versioning.log_archive`, `aws_s3_bucket_public_access_block.log_archive`, `aws_s3_bucket_server_side_encryption_configuration.log_archive` |
| TF-45 | Object Lock default retention COMPLIANCE, 7 years — only when `enforce_locks = true`. | `aws_s3_bucket_object_lock_configuration.log_archive` |
| TF-46 | Lifecycle: transition to GLACIER after 90 days; expire after 2557 days; noncurrent versions expire after 2557 days. | `aws_s3_bucket_lifecycle_configuration.log_archive` |

### File store (`infra/terraform/modules/file-store/main.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-50 | Bucket `<name>-files-<account>`, `force_destroy = false`, versioning on, ACLs disabled, all public access blocked. | `aws_s3_bucket.files`, `aws_s3_bucket_versioning.files`, `aws_s3_bucket_ownership_controls.files`, `aws_s3_bucket_public_access_block.files` |
| TF-51 | SSE-KMS with a dedicated CMK (rotation on, alias `alias/<name>-files`), bucket keys on. | `aws_kms_key.files`, `aws_s3_bucket_server_side_encryption_configuration.files` |
| TF-52 | Bucket policy denies non-TLS access, non-KMS encryption and a foreign KMS key. | `data.aws_iam_policy_document.files`, `aws_s3_bucket_policy.files` |
| TF-53 | Lifecycle: noncurrent versions expire after 7 days; `exports/` current objects after 30 days; `uploads/` no expiry (default 0); incomplete multipart uploads aborted after 1 day. | `aws_s3_bucket_lifecycle_configuration.files` |
| TF-54 | The bucket has no `Backup` tag and no replication: it is outside the AWS Backup plan and has no copy in another region. | (absence) |

### ECS application (`infra/terraform/modules/ecs-app/main.tf`, `mtls.tf`, `variables.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-60 | ECS nodes use the AWS ECS-optimised Amazon Linux 2023 AMI, read from the SSM parameter `/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id` at plan time. | `data.aws_ssm_parameter.ecs_ami`, `aws_launch_template.this` |
| TF-61 | Launch template: IMDSv2 required (hop limit 2), root EBS 60 GB gp3 encrypted, no SSH key pair, `ECS_DISABLE_PRIVILEGED=true`, instance type `m6i.large`. | `aws_launch_template.this` |
| TF-62 | Auto Scaling group across both private app subnets, min 2 / max 4 nodes (production defaults), launch template `$Latest`, no `instance_refresh` block. | `aws_autoscaling_group.this` |
| TF-63 | Container Insights on for the cluster. | `aws_ecs_cluster.this` |
| TF-64 | Nodes reach the shell only through Session Manager (`AmazonSSMManagedInstanceCore`). | `aws_iam_role_policy_attachment.instance_ssm` |
| TF-65 | Execution role may read exactly the injected secret ARNs (no wildcard). | `aws_iam_role_policy.execution_secrets` |
| TF-66 | Task role: object get/put/delete and list on the file-store bucket only; `kms:GenerateDataKey`/`Decrypt` on its CMK only via S3. | `aws_iam_role_policy.task_file_store` |
| TF-67 | App/frontend log group `/ecs/<name>`, retention 90 days in both envs. No KMS key named. | `aws_cloudwatch_log_group.app` |
| TF-68 | Task definition: frontend runs as user `101`, app as `10001:10001`; both drop ALL Linux capabilities and set `no-new-privileges`; frontend `readonlyRootFilesystem = false` (explicit), app not set (default false). | `aws_ecs_task_definition.this` |
| TF-69 | The app container receives the Flyway (RDS master) credentials as `MODOCUMENT_FLYWAY_USERNAME` / `_PASSWORD`, besides the `modocument_app` runtime credentials. | `aws_ecs_task_definition.this` `secrets` |
| TF-70 | App health check `/actuator/health`; frontend health check `/`; ALB target-group health check `/` every 30 s, 3 failures to eject. | `aws_ecs_task_definition.this`, `aws_lb_target_group.this` |
| TF-71 | ALB: internet-facing in the public subnets, `drop_invalid_header_fields = true`; no `access_logs` block; no deletion protection. | `aws_lb.this` |
| TF-72 | HTTPS listener with `ELBSecurityPolicy-TLS13-1-2-2021-06`; port 80 redirects to 443 (301). | `aws_lb_listener.https`, `aws_lb_listener.http_redirect` |
| TF-73 | Service: min 2 tasks (production), `deployment_minimum_healthy_percent = 100`, circuit breaker with rollback; no explicit placement strategy (the ECS service scheduler's default AZ balancing applies). | `aws_ecs_service.this` |
| TF-74 | Autoscaling: CPU target tracking at 60 %, max `var.max_tasks` (production 8, staging 2). | `aws_appautoscaling_target.service`, `aws_appautoscaling_policy.cpu` |
| TF-75 | mTLS listener (off by default): port 8443, `mutual_authentication` mode `verify` against a trust store from a versioned, AES256-encrypted, private S3 bucket; ingress only from `api_mtls_client_cidrs`; only `Host: api_domain` is forwarded. | `infra/terraform/modules/ecs-app/mtls.tf`: `aws_lb_listener.api_mtls`, `aws_lb_trust_store.api`, `aws_s3_bucket.mtls_trust_store`, `aws_security_group_rule.alb_mtls_in` |

### Edge (`infra/terraform/modules/edge/main.tf`, `variables.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-80 | WAF web ACL (scope CLOUDFRONT), default allow, AWS managed rule groups `AWSManagedRulesCommonRuleSet`, `AWSManagedRulesKnownBadInputsRuleSet`, `AWSManagedRulesAmazonIpReputationList`. | `aws_wafv2_web_acl.this` |
| TF-81 | Rate rule `rate-cap`: block above `rate_limit_per_5min` = 2000 requests per IP per 5 minutes. | `aws_wafv2_web_acl.this` rule `rate-cap` |
| TF-82 | Rate rule `rate-cap-auth`: block above `auth_rate_limit_per_5min` = 100 requests per IP per 5 minutes on paths starting `/api/auth/`. | `aws_wafv2_web_acl.this` rule `rate-cap-auth` |
| TF-83 | WAF metrics and sampled requests on; no `aws_wafv2_web_acl_logging_configuration` (no full WAF logs). | `visibility_config` |
| TF-84 | CloudFront: WAF attached, HTTPS-only to the origin (TLSv1.2), viewer minimum `TLSv1.2_2021`, HTTP redirected to HTTPS, IPv6 on, `PriceClass_All`, no geo restriction, no access logging. | `aws_cloudfront_distribution.this` |
| TF-85 | Origin lock: ALB SG admits 443 only from the CloudFront origin-facing managed prefix list; listener rule 1 forwards only with the secret `x-modoc-origin-verify` header; rule 2 answers 403 to everything else. | `aws_security_group_rule.alb_from_cloudfront`, `aws_lb_listener_rule.require_origin_header`, `aws_lb_listener_rule.deny_direct`, `random_password.origin_verify` |
| TF-86 | Egress NACL stub behind `enforce_egress_nacl` (default false). | `aws_network_acl.app_egress` |

### Security monitoring (`infra/terraform/modules/security-monitoring/main.tf`, `variables.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-90 | One SNS alert topic, encrypted with the AWS managed key `alias/aws/sns`; optional e-mail subscription `alert_email`. | `aws_sns_topic.alerts`, `aws_sns_topic_subscription.ops_email` |
| TF-91 | GuardDuty detector enabled, findings every 15 minutes. No `aws_guardduty_detector_feature` (Runtime Monitoring, EBS Malware Protection, S3 Protection) is declared. | `aws_guardduty_detector.this` |
| TF-92 | EventBridge rule sends GuardDuty findings with severity ≥ 4 to the SNS topic. | `aws_cloudwatch_event_rule.guardduty_findings`, `aws_cloudwatch_event_target.guardduty_to_sns` |
| TF-93 | CloudTrail: multi-region, global service events, log-file validation on, management events read and write, to the log-archive bucket (interim, same account). | `aws_cloudtrail.this`, `aws_s3_bucket_policy.trail` |
| TF-94 | CloudTrail CloudWatch log group retention `trail_log_retention_days` = 400; a validation refuses less than 365. No KMS key named. | `aws_cloudwatch_log_group.trail`, `variable "trail_log_retention_days"` |
| TF-95 | Alarms to SNS: ALB target 5xx > 25 in 5 min; ECS service CPU > 85 % for 15 min; RDS free storage < 10 GiB; more than 5 `ACCOUNT_LOCKED` log lines in 15 min (metric filter on the app log group). | `aws_cloudwatch_metric_alarm.alb_5xx`, `.ecs_cpu`, `.rds_storage`, `.account_locked_burst`, `aws_cloudwatch_log_metric_filter.account_locked` |
| TF-96 | No CloudWatch dashboard, no Synthetics canary, no Route 53 health check, no SLO or burn-rate alarm exists in Terraform. | (absence; `grep -E 'dashboard|synthetics|canary|route53'` over `infra/terraform` finds none) |
| TF-97 | Session Manager preferences: sessions logged to the trail bucket prefix `ssm-sessions/<name>/` and to the CloudTrail log group, with encryption flags on; idle timeout 20 min, max 120 min. | `aws_ssm_document.session_preferences` |

### CI deploy identity and registry (`infra/terraform/modules/ci-oidc/main.tf`)

| ID | Fact | Resource / setting |
|---|---|---|
| TF-100 | GitHub OIDC provider and one deploy role per env; trust pinned to `token.actions.githubusercontent.com:sub` patterns; max session 3600 s. | `aws_iam_openid_connect_provider.github`, `aws_iam_role.deploy` |
| TF-101 | ECR repositories `app` and `frontend`: tags IMMUTABLE, `scan_on_push = true`, AES256 encryption. | `aws_ecr_repository.app`, `aws_ecr_repository.frontend` |
| TF-102 | Deploy role may push to the two repositories and register/update the ECS task and service in its own cluster; `iam:PassRole` only for the task and execution roles. | `aws_iam_role_policy.deploy` |

### Absent from Terraform (checked by search on 2026-10-19)

| ID | Not present | Consequence |
|---|---|---|
| TF-110 | Route 53 zones and records (including SPF, DKIM, DMARC, MTA-STS) | DNS is set by hand (`infra/terraform/README.md` apply order: "point DNS at the CloudFront output"). |
| TF-111 | ACM certificates | Created in the console before apply (prereq); referenced by ARN in tfvars. |
| TF-112 | GPU model servers (the design statement's two GPU model servers) | Not in IaC. The only model runtime in the repo is the local `ollama` service in `docker-compose.yml`. |
| TF-113 | AWS Shield Advanced | Shield Standard applies to CloudFront by AWS default; nothing to declare. |
| TF-114 | Log shipping from CloudWatch, RDS or the `AuditEvent` table into the log-archive bucket (subscription filter, Firehose, export job) | The archive receives CloudTrail and Session Manager logs only. |
| TF-115 | AWS Config rules, Security Hub, Inspector, CIS benchmark scans | No CIS or posture scan of the node image exists in IaC or CI. |

## 4. Defects and inconsistencies found

Severity is A8's view; the owner decides. No Terraform or application code
was changed (documentation-only task).

| # | Severity | Finding | Evidence | Suggested fix |
|---|---|---|---|---|
| DEF-1 | High (blocks first production start) | Production enables the interface endpoints with the **app** security group as the endpoint SG. That SG admits only the ALB on 8080/8443, not 443 from the tasks and nodes. With private DNS on, calls to Secrets Manager, ECR, CloudWatch Logs and SSM resolve to the endpoints and are refused, so tasks cannot pull images or secrets. `validate` cannot see this. | TF-17, TF-15; `infra/terraform/envs/production/main.tf` `enable_interface_endpoints = true` | A dedicated endpoint SG allowing 443 from the app SG. |
| DEF-2 | Medium | The alert topic uses the AWS managed key `alias/aws/sns`. EventBridge (GuardDuty findings) and CloudWatch alarms cannot publish to a topic encrypted with the AWS managed SNS key, so findings and alarms would not be delivered. | TF-90, TF-92, TF-95 | A customer-managed KMS key whose policy allows `events.amazonaws.com` and `cloudwatch.amazonaws.com`. Prove with a test alarm after apply. |
| DEF-3 | Medium | GuardDuty Runtime Monitoring and Malware Protection, chosen on 2 Oct 2026, are not declared. | TF-91 | `aws_guardduty_detector_feature` resources (RUNTIME_MONITORING with ECS/EC2 agent management, EBS_MALWARE_PROTECTION, S3_DATA_EVENTS). |
| DEF-4 | Medium | The S3 file store (uploads, exports, document assets) is outside AWS Backup, has no cross-region copy and no Object Lock. Recovery after deletion or encryption relies on versioning with a 7-day noncurrent window, and the task role may delete objects. | TF-43, TF-53, TF-54, TF-66 | Tag the bucket for AWS Backup (S3 backup) or replicate to a locked bucket; document the choice in PRIV-006. |
| DEF-5 | Medium | Nothing ships the `AuditEvent` table, the app log group or the RDS logs to the 7-year archive. `docs/security/data-inventory.md` rows 14 and 15 say "7y archive (IaC)". | TF-114, TF-67, TF-29 | Subscription filter / Firehose for log groups; a scheduled `AuditEvent` export to the archive. |
| DEF-6 | Low | RDS log exports create log groups that Terraform does not manage: no retention (CloudWatch default is never expire) and no CMK. | TF-29 | Declare `/aws/rds/instance/<id>/postgresql` and `/upgrade` log groups with retention. |
| DEF-7 | Low (verify at apply) | Session Manager preferences ask for CloudWatch encryption, but the target log group names no KMS key; the node role has no `s3:PutObject` on the trail bucket. Session logging may fail at first use. | TF-97, TF-94, TF-64 | Encrypt the log group with a CMK; grant the node role write to the `ssm-sessions/` prefix. |
| DEF-8 | Low (latent, flag off) | The egress NACL stub allows inbound only from the VPC CIDR. NACLs are stateless, so return traffic from allow-listed internet hosts (through NAT) would be dropped once `enforce_egress_nacl = true`. | TF-86 | Allow inbound ephemeral ports from the allow-listed CIDRs. |
| DEF-9 | Low | No access logs for the ALB or CloudFront and no WAF logging. DDoS and incident analysis would rely on metrics and sampled requests only. | TF-71, TF-83, TF-84 | Add logging to the log-archive bucket (or a separate bucket). |
| DEF-10 | Low | Node OS patching: the AMI is resolved at plan time and the ASG has no `instance_refresh`, so running nodes are replaced only by a manual refresh. | TF-60, TF-62 | `instance_refresh` block or ECS managed instance draining on a monthly refresh. |
| DEF-11 | Low | The app task holds the RDS master (Flyway) credentials at run time. The workbook asks for migrations under a separate account used only by the pipeline. | TF-69, TF-28 | Run Flyway as a one-off task or CI step; remove the master secret from the service task. |
| DEF-12 | Low | Connector key rotation needs `MODOCUMENT_CONNECTOR_KEY_VERSION` and `MODOCUMENT_CONNECTOR_RETIRED_KEYS` (`src/main/resources/application.yml`), which the task definition does not inject. | TF-68; `src/main/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVault.java` | Add both (the retired keys as a secret) before the first rotation. |
| DEF-13 | Low | `engine_version = "17.4"` is pinned to a minor version while `auto_minor_version_upgrade = true`. After AWS applies a minor upgrade, the next `terraform plan` shows a change back to 17.4 (a downgrade RDS refuses). | TF-20, TF-26 | Set `engine_version = "17"` (major only) or turn the automatic upgrade off and patch through Terraform. |
| DOC-1 | Doc | `infra/terraform/modules/ecs-app/outputs.tf` says the edge module attaches a regional WAF to the ALB in staging. No such resource exists; staging has no WAF. | TF-07 | Correct the description (Terraform file, not changed here). |
| DOC-2 | Doc | `docs/release/runbook.md` §7 uses `eu-west-1` in its examples; the region is `af-south-1`. | TF-01 | Correct the examples. |
| DOC-3 | Doc | `docs/security/data-inventory.md` rows 6 and 8 still say uploads and exports live on a disk volume (now the S3 file store, TF-50), and row 15 says 400 days hot (now 90, TF-67). | TF-50, TF-67 | Update the inventory. |
| DOC-4 | Doc | Gate numbering differs: the root `README.md` "Implement later" table uses A3–A5 for VPC/edge, while the Go-Live Security Gate (2 Oct 2026) uses A3 = MFA, A4 = admin identities, A5 = root sealed. The workbook cites gate items A9–A20, which the 2 Oct gate document does not contain. | `README.md` | Align on one numbering. |
| DOC-5 | Doc | `infra/terraform/README.md` "Apply order" numbered two steps "6." | — | Fixed by A8 in this commit (text only). |
| DOC-6 | Design | Workbook says PITR is kept 90 days. The code keeps PITR 35 days (RDS maximum) and daily snapshots 90 days. | TF-25, TF-42 | Use the code's wording in answers (done in PRIV-006). |
| DOC-7 | Design | RTO: workbook (SEC-005 comment) says 2 hours; `docs/security/sla-and-status.md` says 4 hours. Critical-patch SLA: workbook says 24 hours; `docs/release/runbook.md` §6 says 72 hours. | — | `TO CONFIRM (organisational)`: one value each. |
| DOC-8 | Design | The design statement lists two GPU model servers; they are not in Terraform. | TF-112 | Add to IaC or record as out of scope for go-live. |
