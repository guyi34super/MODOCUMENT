# ===========================================================================
# MoDocument — PRODUCTION environment (af-south-1)
#
# Wires the seven modules into the go-live topology. See ../../README.md for
# the apply order and the two-account model. terraform validate passes;
# plan/apply are pending — nothing here has been applied.
# ===========================================================================

data "aws_caller_identity" "current" {}

locals {
  name = "modocument-prod"

  # The frontend nginx is nginxinc/nginx-unprivileged on 8080 (ws16,
  # SUP-S04): ALB -> task traffic, the SG rule and the target group all use
  # this one value.
  frontend_port = 8080

  tags = {
    Project     = "modocument"
    Environment = "production"
    ManagedBy   = "terraform"
  }
}

# --- Secret shells (SEC-018) -------------------------------------------------
# Terraform creates the CONTAINERS only; the values are set out-of-band
# (aws secretsmanager put-secret-value) by the key owner, so no secret ever
# enters tfvars or state. Register every one of these in
# docs/security/key-certificate-register.md.

resource "aws_secretsmanager_secret" "jwt" {
  name        = "${local.name}/MODOCUMENT_JWT_SECRET"
  description = "HS256 signing key, >=32 bytes (openssl rand -base64 48). Rotation: see docs/security/crypto-standard.md."

  tags = local.tags
}

resource "aws_secretsmanager_secret" "mfa_key" {
  name        = "${local.name}/MODOCUMENT_MFA_KEY"
  description = "AES-256-GCM key material for TOTP secrets at rest (>=32 bytes)."

  tags = local.tags
}

# ws25/26: AES-256-GCM key of the connector SecretVault (bank integration
# credentials at rest), separate from the MFA key. 32 random bytes, base64.
resource "aws_secretsmanager_secret" "connector_key" {
  name        = "${local.name}/MODOCUMENT_CONNECTOR_KEY"
  description = "AES-256-GCM key for bank integration credentials at rest (>=32 bytes, openssl rand -base64 32)."

  tags = local.tags
}

resource "aws_secretsmanager_secret" "db_app" {
  name        = "${local.name}/db-app-credentials"
  description = "JSON {username,password} for the modocument_app (NOBYPASSRLS) runtime role."

  tags = local.tags
}

# First-boot administrator (BootstrapAdminRunner): on an EMPTY AppUsers table
# the app refuses to start without this pair; once any user exists the runner
# is a no-op. Values must be in place BEFORE the first task starts (README
# "Apply order"); after the first admin has signed in, changed the password
# and enrolled MFA, set bootstrap_admin_enabled = false and re-apply — the
# task definition stops injecting the pair, the execution role loses read
# access and both secrets are scheduled for deletion.
resource "aws_secretsmanager_secret" "bootstrap_admin_email" {
  count       = var.bootstrap_admin_enabled ? 1 : 0
  name        = "${local.name}/MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL"
  description = "E-mail of the first ADMIN, created only when AppUsers is empty. Remove (bootstrap_admin_enabled=false) once that admin has enrolled MFA."

  tags = local.tags
}

resource "aws_secretsmanager_secret" "bootstrap_admin_password" {
  count       = var.bootstrap_admin_enabled ? 1 : 0
  name        = "${local.name}/MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD"
  description = "Initial password of the first ADMIN (password policy applies). Changed in-app at first login; remove (bootstrap_admin_enabled=false) once MFA is enrolled."

  tags = local.tags
}

# --- Modules ------------------------------------------------------------------

module "network" {
  source = "../../modules/network"

  name                       = local.name
  region                     = var.region
  vpc_cidr                   = var.vpc_cidr
  app_port                   = local.frontend_port
  bank_egress_cidrs          = var.bank_egress_cidrs
  app_egress_https_cidrs     = var.app_egress_https_cidrs
  enable_interface_endpoints = true
  tags                       = local.tags
}

module "rds" {
  source = "../../modules/rds"

  name                 = local.name
  db_subnet_ids        = module.network.private_db_subnet_ids
  db_security_group_id = module.network.db_security_group_id
  instance_class       = var.db_instance_class
  deletion_protection  = true
  tags                 = local.tags
}

module "backups" {
  source = "../../modules/backups"

  providers = {
    aws         = aws
    aws.replica = aws.backup_replica
  }

  name           = local.name
  account_suffix = data.aws_caller_identity.current.account_id
  # Flip to true ONLY after the restore drill (gate D6) passes — compliance
  # locks are irreversible.
  enforce_locks = var.enforce_backup_locks
  tags          = local.tags
}

module "ecs_app" {
  source = "../../modules/ecs-app"

  name                   = local.name
  region                 = var.region
  vpc_id                 = module.network.vpc_id
  public_subnet_ids      = module.network.public_subnet_ids
  private_app_subnet_ids = module.network.private_app_subnet_ids
  alb_security_group_id  = module.network.alb_security_group_id
  app_security_group_id  = module.network.app_security_group_id

  app_image      = var.app_image
  frontend_image = var.frontend_image
  frontend_port  = local.frontend_port

  certificate_arn = var.alb_certificate_arn

  # verify-full: TLS + server certificate chain + host name, against the AWS
  # RDS CA bundle the app image carries (repo Dockerfile, rds-ca stage).
  db_jdbc_url                   = "jdbc:postgresql://${module.rds.db_endpoint}/modocument?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem"
  jwt_secret_arn                = aws_secretsmanager_secret.jwt.arn
  mfa_key_secret_arn            = aws_secretsmanager_secret.mfa_key.arn
  db_app_credentials_secret_arn = aws_secretsmanager_secret.db_app.arn
  db_master_secret_arn          = module.rds.master_user_secret_arn
  connector_key_secret_arn      = aws_secretsmanager_secret.connector_key.arn

  # ws26: mutual TLS for machine clients on api.<domain> (off by default).
  api_mtls_enabled       = var.api_mtls_enabled
  api_domain             = var.api_domain
  api_certificate_arn    = var.api_certificate_arn
  api_mtls_ca_bundle_pem = var.api_mtls_ca_bundle_pem
  api_mtls_client_cidrs  = var.api_mtls_client_cidrs
  # The ALB nodes: the only peers allowed to vouch for a client certificate.
  mtls_trusted_proxy_cidrs = module.network.public_subnet_cidrs
  bootstrap_admin_secret_arns = var.bootstrap_admin_enabled ? {
    email    = one(aws_secretsmanager_secret.bootstrap_admin_email[*].arn)
    password = one(aws_secretsmanager_secret.bootstrap_admin_password[*].arn)
  } : null

  cors_allowed_origins = "https://${var.app_domain}"
  deploy_environment   = "production"

  # client -> CloudFront -> ALB -> nginx: the ALB (inside the VPC) is the
  # trusted proxy; the client address is the CloudFront-Viewer-Address the
  # edge module's origin request policy adds (the rightmost untrusted
  # X-Forwarded-For hop would be a CloudFront edge node, not the user).
  trusted_proxy_cidrs = [var.vpc_cidr]
  public_host         = var.app_domain
  client_ip_header    = "CloudFront-Viewer-Address"

  min_tasks = 2
  max_tasks = var.max_tasks

  tags = local.tags
}

module "edge" {
  source = "../../modules/edge"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name                       = local.name
  vpc_id                     = module.network.vpc_id
  vpc_cidr                   = var.vpc_cidr
  private_app_subnet_ids     = module.network.private_app_subnet_ids
  alb_dns_name               = module.ecs_app.alb_dns_name
  alb_security_group_id      = module.network.alb_security_group_id
  alb_https_listener_arn     = module.ecs_app.alb_https_listener_arn
  alb_target_group_arn       = module.ecs_app.target_group_arn
  domain_aliases             = [var.app_domain]
  cloudfront_certificate_arn = var.cloudfront_certificate_arn
  enforce_egress_nacl        = var.enforce_egress_nacl
  egress_allowlist_cidrs     = var.egress_allowlist_cidrs
  tags                       = local.tags
}

module "security_monitoring" {
  source = "../../modules/security-monitoring"

  name = local.name

  # Interim (pre-A6): the local Object-Lock bucket. Target: the lock
  # account's bucket — change these two values + the provider note in
  # providers.tf, nothing else.
  trail_bucket               = module.backups.log_archive_bucket
  trail_bucket_arn           = module.backups.log_archive_bucket_arn
  manage_trail_bucket_policy = true

  alert_email        = var.alert_email
  alb_arn_suffix     = module.ecs_app.alb_arn_suffix
  ecs_cluster_name   = module.ecs_app.cluster_name
  ecs_service_name   = module.ecs_app.service_name
  rds_instance_id    = module.rds.db_instance_id
  app_log_group_name = module.ecs_app.log_group_name

  tags = local.tags
}

module "ci_oidc" {
  source = "../../modules/ci-oidc"

  name                 = local.name
  create_oidc_provider = var.create_oidc_provider
  oidc_provider_arn    = var.oidc_provider_arn
  # ws24: production deploys run in the GitHub environment "production"
  # (required reviewers, v* tags only — docs/release/runbook.md), whose
  # jobs present sub = repo:<repo>:environment:production.
  # TRANSITION: the refs/heads/main entry is the pre-ws24 trust (pair with
  # branch protection — ws19). Remove it once one release has shipped
  # through the environment-gated pipeline.
  github_subject_patterns = [
    "repo:${var.github_repository}:environment:production",
    "repo:${var.github_repository}:ref:refs/heads/main", # remove after one release
  ]
  ecs_cluster_arn    = module.ecs_app.cluster_arn
  passable_role_arns = module.ecs_app.task_role_arns

  tags = local.tags
}

# --- Outputs ------------------------------------------------------------------

output "cloudfront_domain" {
  value       = module.edge.distribution_domain_name
  description = "Point the public DNS at this."
}

output "deploy_role_arn" {
  value       = module.ci_oidc.deploy_role_arn
  description = "role-to-assume for GitHub Actions (SUP-S05/A8)."
}

output "alert_topic_arn" {
  value       = module.security_monitoring.alert_topic_arn
  description = "ws18 alert-channel seam."
}

output "log_archive_bucket" {
  value       = module.backups.log_archive_bucket
  description = "7y WORM archive (SEC-033)."
}
