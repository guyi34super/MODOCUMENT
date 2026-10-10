# Remote state — PLACEHOLDER (uncomment and fill once the state bucket and
# lock table exist in the production account; create them by hand or with a
# minimal bootstrap stack — never store state in the repo).
#
# terraform {
#   backend "s3" {
#     bucket         = "modocument-terraform-state-<ACCOUNT_ID>"
#     key            = "production/terraform.tfstate"
#     region         = "af-south-1"
#     dynamodb_table = "modocument-terraform-locks"
#     encrypt        = true
#   }
# }
#
# Until then Terraform defaults to local state: acceptable for validate/plan
# review only — DO NOT apply with local state.
