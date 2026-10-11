# ---------------------------------------------------------------------------
# Module: file-store — the S3 bucket behind the app's FileStore (ws29, v1.2.0)
#
# Uploads, ingestion export copies and doc-creation assets/exports move off
# the container disk (lost on every task replacement, never shared between
# tasks) into one bucket per environment. The app writes the SAME relative
# key layout it used on disk (uploads/<name>, exports/<name>,
# exports/u<owner>/job<id>.<ext>) — see docs/optimization/contracts.md §7.
#
# Gate / control mapping:
#   SEC-028 / C4  Encrypted at rest with a dedicated, rotating CMK (SSE-KMS,
#                 S3 Bucket Keys to keep KMS calls cheap); TLS-only bucket
#                 policy (aws:SecureTransport) for every principal.
#   SUP-S06       No public access in any form (all four public-access-block
#                 switches, BucketOwnerEnforced — ACLs disabled).
#   C5 / PRIV-013 The app's daily RetentionPurgeJob stays the authority on
#                 the 30-day deletion (it deletes the objects with the rows).
#                 The lifecycle rules here are the storage-side backstop:
#                   * exports/: current objects expire after
#                     var.exports_expiry_days (30) — every export is
#                     regenerable and the purge clears them on the same clock;
#                   * uploads/: var.uploads_expiry_days, OFF (0) by default —
#                     uploads/ also holds doc-creation BLUEPRINTS, which are
#                     configuration and never purged (RetentionPurger), and
#                     the image assets of documents still being edited; a
#                     blanket expiry would delete both. Turn it on only when
#                     those move to their own prefix;
#                   * every deleted/overwritten object version (what a purge
#                     or a regeneration leaves behind in a versioned bucket)
#                     is removed after var.noncurrent_version_days (7), so
#                     purged customer data is physically gone within days;
#                   * incomplete multipart uploads are aborted after 1 day.
#   Least privilege The ECS task role (ecs-app) gets object read/write/delete
#                 and list on THIS bucket only, plus use of THIS key.
#
# Traffic stays private: the network module's S3 GATEWAY endpoint is on the
# app route tables, so the tasks reach S3 without the NAT gateways.
#
# NOT APPLIED — `terraform validate` passes; plan/apply pending.
# ---------------------------------------------------------------------------

resource "aws_kms_key" "files" {
  description             = "${var.name} file store (S3 SSE-KMS) — uploads, exports, doc assets"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = var.tags
}

resource "aws_kms_alias" "files" {
  name          = "alias/${var.name}-files"
  target_key_id = aws_kms_key.files.key_id
}

resource "aws_s3_bucket" "files" {
  bucket = "${var.name}-files-${var.account_suffix}"

  # Customer data: never let a `terraform destroy` empty it implicitly.
  force_destroy = false

  tags = merge(var.tags, { Purpose = "app-file-store (ws29)" })
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket = aws_s3_bucket.files.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "files" {
  bucket = aws_s3_bucket.files.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.files.arn
    }
    bucket_key_enabled = true
  }
}

data "aws_iam_policy_document" "files" {
  # TLS only, for every principal (SEC-028).
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.files.arn,
      "${aws_s3_bucket.files.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # Objects are written with SSE-KMS under THIS key: a PUT that asks for
  # another algorithm (e.g. AES256) or names another KMS key is refused.
  # A PUT that names neither gets the bucket default — the same CMK.
  # (Null=false: the header is present; conditions in a statement AND.)
  statement {
    sid       = "DenyNonKmsEncryption"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.files.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Null"
      variable = "s3:x-amz-server-side-encryption"
      values   = ["false"]
    }

    condition {
      test     = "StringNotEquals"
      variable = "s3:x-amz-server-side-encryption"
      values   = ["aws:kms"]
    }
  }

  statement {
    sid       = "DenyForeignKmsKey"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.files.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Null"
      variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
      values   = ["false"]
    }

    condition {
      test     = "StringNotEquals"
      variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
      values   = [aws_kms_key.files.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "files" {
  bucket = aws_s3_bucket.files.id
  policy = data.aws_iam_policy_document.files.json

  depends_on = [aws_s3_bucket_public_access_block.files]
}

resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  # Versioning must be on before noncurrent-version rules mean anything.
  depends_on = [aws_s3_bucket_versioning.files]

  rule {
    id     = "noncurrent-versions-and-cleanup"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  # Delete markers left once their last noncurrent version expired.
  rule {
    id     = "expired-delete-markers"
    status = "Enabled"

    filter {}

    expiration {
      expired_object_delete_marker = true
    }
  }

  rule {
    id     = "exports-current-expiry"
    status = "Enabled"

    filter {
      prefix = "exports/"
    }

    expiration {
      days = var.exports_expiry_days
    }
  }

  dynamic "rule" {
    for_each = var.uploads_expiry_days > 0 ? [var.uploads_expiry_days] : []
    content {
      id     = "uploads-current-expiry"
      status = "Enabled"

      filter {
        prefix = "uploads/"
      }

      expiration {
        days = rule.value
      }
    }
  }
}
