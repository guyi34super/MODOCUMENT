# Remote state — PLACEHOLDER. Staging has its OWN state bucket in its OWN
# account (SUP-S07); never share a backend with production.
#
# terraform {
#   backend "s3" {
#     bucket         = "modocument-terraform-state-<STAGING_ACCOUNT_ID>"
#     key            = "staging/terraform.tfstate"
#     region         = "af-south-1"
#     dynamodb_table = "modocument-terraform-locks"
#     encrypt        = true
#   }
# }
