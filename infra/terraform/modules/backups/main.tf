# ---------------------------------------------------------------------------
# Module: backups — immutable backups + 7-year log archive
#
# Gate / control mapping:
#   D4 / PRIV-006  90-day backup retention + cross-region copy (ap-southeast-2).
#   D5 / PRIV-003  Backup vault LOCK (compliance mode): ransomware or a
#                  compromised admin cannot shorten or delete recovery points.
#                  The vault is designed to live in a SEPARATE backup account
#                  (two-account model — see the root README); the stub below
#                  uses an aliased provider so the copy destination can point
#                  at that account once it exists (gate A1/A6 build-out).
#   SEC-033        7-year append-only log archive: S3 Object Lock COMPLIANCE
#                  mode, Glacier lifecycle — the AuditEvent table's export
#                  destination and CloudTrail/app-log archive.
#
# IMPORTANT: vault-lock compliance mode and Object Lock compliance mode are
# IRREVERSIBLE once the cooling-off period ends. Apply to staging first with
# var.enforce_locks=false, flip to true only in production.
#
# NOT APPLIED — `terraform validate` pending a machine with terraform.
# ---------------------------------------------------------------------------

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      # The copy/DR region (D4: ap-southeast-2). The root env wires this
      # alias; the separate backup ACCOUNT (D5) additionally needs its own
      # provider credentials — stubbed in envs/production/providers.tf.
      configuration_aliases = [aws.replica]
    }
  }
}

# --- AWS Backup: locked vault + plan --------------------------------------

resource "aws_backup_vault" "primary" {
  name          = "${var.name}-vault"
  force_destroy = false

  tags = var.tags
}

resource "aws_backup_vault" "replica" {
  provider      = aws.replica
  name          = "${var.name}-vault-replica"
  force_destroy = false

  tags = var.tags
}

# D5: compliance-mode vault lock. min/max retention bound what any future
# backup plan may set; changeable_for_days is the one-way door.
resource "aws_backup_vault_lock_configuration" "primary" {
  count = var.enforce_locks ? 1 : 0

  backup_vault_name   = aws_backup_vault.primary.name
  changeable_for_days = 3
  min_retention_days  = var.backup_retention_days
  max_retention_days  = 366
}

resource "aws_backup_vault_lock_configuration" "replica" {
  count    = var.enforce_locks ? 1 : 0
  provider = aws.replica

  backup_vault_name   = aws_backup_vault.replica.name
  changeable_for_days = 3
  min_retention_days  = var.backup_retention_days
  max_retention_days  = 366
}

resource "aws_backup_plan" "this" {
  name = "${var.name}-daily"

  rule {
    rule_name         = "daily-90d-crossregion"
    target_vault_name = aws_backup_vault.primary.name
    schedule          = "cron(30 0 * * ? *)" # 00:30 UTC daily

    lifecycle {
      delete_after = var.backup_retention_days # D4 / PRIV-006: 90 days
    }

    copy_action {
      destination_vault_arn = aws_backup_vault.replica.arn

      lifecycle {
        delete_after = var.backup_retention_days
      }
    }
  }

  tags = var.tags
}

resource "aws_iam_role" "backup" {
  name_prefix = "${var.name}-backup-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "backup.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_iam_role_policy_attachment" "restore" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores"
}

# Select everything tagged Backup=<selection_tag> (the RDS module tags its
# instance; add the uploads EFS/S3 when file storage moves off the container
# volume).
resource "aws_backup_selection" "tagged" {
  name         = "${var.name}-tagged"
  plan_id      = aws_backup_plan.this.id
  iam_role_arn = aws_iam_role.backup.arn

  selection_tag {
    type  = "STRINGEQUALS"
    key   = "Backup"
    value = var.backup_selection_tag
  }
}

# --- S3 log archive: 7y WORM (SEC-033) -------------------------------------

resource "aws_s3_bucket" "log_archive" {
  bucket = "${var.name}-log-archive-${var.account_suffix}"

  # Object Lock must be enabled at creation; it cannot be retrofitted.
  object_lock_enabled = true

  tags = merge(var.tags, { Purpose = "7y-worm-log-archive (SEC-033)" })
}

resource "aws_s3_bucket_versioning" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "log_archive" {
  count  = var.enforce_locks ? 1 : 0
  bucket = aws_s3_bucket.log_archive.id

  rule {
    default_retention {
      mode  = "COMPLIANCE" # irreversible WORM — nobody shortens the 7 years
      years = 7
    }
  }
}

resource "aws_s3_bucket_public_access_block" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id

  rule {
    id     = "glacier-then-expire"
    status = "Enabled"

    filter {}

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = 2557 # 7 years — matches the Object Lock default retention
    }

    noncurrent_version_expiration {
      noncurrent_days = 2557
    }
  }
}
