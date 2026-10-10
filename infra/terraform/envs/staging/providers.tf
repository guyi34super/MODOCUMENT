# Staging providers — SEPARATE ACCOUNT from production (SUP-S07): staging
# runs with its own credentials, state backend and secrets; no shared IAM,
# no shared data. Same af-south-1 region so parity testing is honest.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
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

# Cross-region backup copy (kept in staging so the D4 pipeline is rehearsed
# end-to-end before production relies on it).
provider "aws" {
  alias  = "backup_replica"
  region = "ap-southeast-2"

  default_tags {
    tags = local.tags
  }
}
