variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "account_suffix" {
  description = "Account-unique suffix for globally-named S3 buckets (e.g. the account id)."
  type        = string
}

variable "backup_retention_days" {
  description = "Recovery-point retention (D4/PRIV-006: 90 days, both regions)."
  type        = number
  default     = 90
}

variable "backup_selection_tag" {
  description = "Backup tag value selected into the daily plan."
  type        = string
  default     = "modocument"
}

variable "enforce_locks" {
  description = <<-EOT
    true = apply vault lock (compliance) and S3 Object Lock COMPLIANCE
    default retention. IRREVERSIBLE after the cooling-off window — keep false
    in staging, true in production once the restore drill (gate D6) passed.
  EOT
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
