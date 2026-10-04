# ---------------------------------------------------------------------------
# Module: rds — PostgreSQL for MoDocument
#
# Gate / control mapping:
#   SEC-004   Multi-AZ: a single-AZ failure fails over automatically.
#   SEC-028 / gate C4   Storage encryption at rest (KMS CMK).
#   SUP-S06   TLS forced on every client connection (rds.force_ssl=1) —
#             pairs with the network module's "db SG only from app SG".
#   D4        Automated backups + point-in-time recovery; the backups module
#             adds the locked vault + cross-region copy on top.
#
# TWO-ROLE PROVISIONING NOTE (not a Terraform concern, run once per cluster):
# the application connects as `modocument_app` (NOBYPASSRLS, subject to the
# V13 row-level-security tenant policies) while Flyway migrates as the schema
# owner. The model for creating that role safely — password via a psql
# variable, never string-spliced — is docker/postgres-init/01-app-role.sh in
# this repository; replay it against the RDS endpoint as the master user
# after the first `terraform apply` (migration V12 also creates the role with
# a placeholder password; set the real one with ALTER ROLE). Store both
# passwords in Secrets Manager (see ecs-app module), never in tfvars.
#
# Performance Insights: deliberately OFF — it samples live SQL text, which in
# this application can contain customer PII; revisit only with an explicit
# DPIA entry (docs/security/dpia-template.md).
#
# NOT APPLIED — `terraform validate` pending a machine with terraform.
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "this" {
  name_prefix = "${var.name}-"
  subnet_ids  = var.db_subnet_ids

  tags = merge(var.tags, { Name = "${var.name}-db-subnets" })
}

resource "aws_kms_key" "db" {
  description             = "${var.name} RDS storage encryption (SEC-028 / gate C4)"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = var.tags
}

resource "aws_kms_alias" "db" {
  name          = "alias/${var.name}-rds"
  target_key_id = aws_kms_key.db.key_id
}

# SUP-S06: refuse any non-TLS connection at the server.
resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name}-pg17-"
  family      = "postgres17"
  description = "${var.name}: forced TLS + sane logging"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # DDL and role changes are evidence (SEC-033 feeds on database logs too).
  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier_prefix = "${var.name}-"
  engine            = "postgres"
  engine_version    = var.engine_version
  instance_class    = var.instance_class

  db_name  = "modocument"
  username = var.master_username
  # Master password is generated and owned by RDS/Secrets Manager — it never
  # touches Terraform state as a variable (SEC-018 posture).
  manage_master_user_password = true

  multi_az          = true # SEC-004
  storage_encrypted = true # SEC-028 / C4
  kms_key_id        = aws_kms_key.db.arn

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.db_security_group_id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  port                   = 5432

  # D4: automated backups + PITR. The backups module layers the locked
  # AWS Backup vault and the ap-southeast-2 cross-region copy on top.
  backup_retention_period   = var.backup_retention_days
  backup_window             = "01:00-02:00" # before the app's 02:41 retention purge
  maintenance_window        = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot     = true
  delete_automated_backups  = false
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name}-final"

  # OFF by design: samples live SQL (customer PII risk). See header note.
  performance_insights_enabled = false

  auto_minor_version_upgrade = true
  apply_immediately          = false

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = merge(var.tags, {
    Name   = "${var.name}-postgres"
    Backup = var.backup_selection_tag # picked up by the backups module's selection
  })
}
