# Production providers — af-south-1 primary (design decision, 2 Oct 2026).
#
# TWO-ACCOUNT MODEL (root README):
#   * production account  — everything in this env.
#   * lock account        — CloudTrail/log-archive bucket + backup vault copy
#     (SEC-015/A6, PRIV-003/D5). Until that account exists (gate A1/A6 is
#     organisational), the aliased providers below point at the SAME account
#     in the right regions; switching them to assume_role into the lock
#     account is a provider-only change — no resource moves.
#   * staging lives in its own separate account (SUP-S07) with its own state.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.68"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = "af-south-1"

  default_tags {
    tags = local.tags
  }
}

# CloudFront viewer certificate + CLOUDFRONT-scope WAF must be us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.tags
  }
}

# Cross-region backup copies (D4/PRIV-006: ap-southeast-2). Point this at the
# LOCK ACCOUNT with an assume_role block once gate A6 is executed:
#
#   assume_role {
#     role_arn = "arn:aws:iam::<LOCK_ACCOUNT_ID>:role/modocument-backup-admin"
#   }
provider "aws" {
  alias  = "backup_replica"
  region = "ap-southeast-2"

  default_tags {
    tags = local.tags
  }
}
