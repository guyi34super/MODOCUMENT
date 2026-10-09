# ===========================================================================
# MoDocument — STAGING environment (af-south-1, SEPARATE account — SUP-S07)
#
# Same modules as production, scaled down, with these deliberate deltas:
#   * no CloudFront/WAF edge module — the ALB takes HTTPS directly (the edge
#     stack is exercised in production's pre-go-live dry run instead; the
#     k6 load test E7 runs against THIS ALB);
#   * backup/Object locks OFF (enforce_locks=false) — locks are irreversible
#     and staging data is disposable; the restore drill (D6) still rehearses
#     here against unlocked vaults;
#   * CI OIDC trust is repo-wide (any branch may deploy to staging).
#
# terraform validate PENDING a machine with terraform; nothing applied.
# ===========================================================================

data "aws_caller_identity" "current" {}

locals {
  name = "modocument-staging"

  # The frontend nginx is nginxinc/nginx-unprivileged on 8080 (ws16,
  # SUP-S04): ALB -> task traffic, the SG rule and the target group all use
  # this one value.
  frontend_port = 8080

  tags = {
    Project     = "modocument"
    Environment = "staging"
    ManagedBy   = "terraform"
  }
}

# Secret shells (SEC-018) — staging has its OWN secrets, never production's.
resource "aws_secretsmanager_secret" "jwt" {
  name = "${local.name}/MODOCUMENT_JWT_SECRET"
  tags = local.tags
}

resource "aws_secretsmanager_secret" "mfa_key" {
  name = "${local.name}/MODOCUMENT_MFA_KEY"
  tags = local.tags
}

resource "aws_secretsmanager_secret" "db_app" {
  name = "${local.name}/db-app-credentials"
  tags = local.tags
}

module "network" {
  source = "../../modules/network"

  name     = local.name
  region   = var.region
  vpc_cidr = var.vpc_cidr
  app_port = local.frontend_port
  tags     = local.tags
}

# Staging has no CloudFront in front: the ALB accepts HTTPS from the test
# networks directly (narrow var.ingress_cidrs to the office/VPN ranges).
resource "aws_security_group_rule" "alb_https_in" {
  type              = "ingress"
  security_group_id = module.network.alb_security_group_id
  protocol          = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_blocks       = var.ingress_cidrs
  description       = "Staging ALB ingress (no CloudFront tier here)"
}

module "rds" {
  source = "../../modules/rds"

  name                 = local.name
  db_subnet_ids        = module.network.private_db_subnet_ids
  db_security_group_id = module.network.db_security_group_id
  instance_class       = var.db_instance_class
  deletion_protection  = false
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
  enforce_locks  = false # never lock staging — locks are irreversible
  tags           = local.tags
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

  db_jdbc_url                   = "jdbc:postgresql://${module.rds.db_endpoint}/modocument?sslmode=verify-full"
  jwt_secret_arn                = aws_secretsmanager_secret.jwt.arn
  mfa_key_secret_arn            = aws_secretsmanager_secret.mfa_key.arn
  db_app_credentials_secret_arn = aws_secretsmanager_secret.db_app.arn
  db_master_secret_arn          = module.rds.master_user_secret_arn

  cors_allowed_origins = "https://${var.app_domain}"

  instance_type = var.instance_type
  min_instances = 1
  max_instances = 2
  min_tasks     = 1
  max_tasks     = 2

  tags = local.tags
}

module "security_monitoring" {
  source = "../../modules/security-monitoring"

  name = local.name

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
  # Any branch of the repo may deploy to staging.
  github_subject_patterns = ["repo:${var.github_repository}:*"]
  ecs_cluster_arn         = module.ecs_app.cluster_arn
  passable_role_arns      = module.ecs_app.task_role_arns

  tags = local.tags
}

output "alb_dns_name" {
  value       = module.ecs_app.alb_dns_name
  description = "Staging entry point (also the gate E7 k6 target)."
}

output "deploy_role_arn" {
  value       = module.ci_oidc.deploy_role_arn
  description = "role-to-assume for GitHub Actions staging deploys."
}
