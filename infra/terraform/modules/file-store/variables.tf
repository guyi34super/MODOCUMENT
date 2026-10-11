variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "account_suffix" {
  description = "Account id (or another globally unique suffix) appended to the bucket name — S3 names are global."
  type        = string
}

variable "exports_expiry_days" {
  description = "Days after which current objects under exports/ expire (regenerable export copies and generated documents; the app's 30-day retention purge runs on the same clock)."
  type        = number
  default     = 30

  validation {
    condition     = var.exports_expiry_days >= 30
    error_message = "exports_expiry_days must be at least 30: the app's retention window is 30 days, the bucket must never delete earlier."
  }
}

variable "uploads_expiry_days" {
  description = "Days after which current objects under uploads/ expire; 0 = no storage-side expiry (the default). uploads/ also holds doc-creation blueprints (configuration, never purged) and the assets of documents still being edited — the app's RetentionPurgeJob deletes the customer uploads after 30 days instead. Only set this once those live under their own prefix."
  type        = number
  default     = 0

  validation {
    condition     = var.uploads_expiry_days == 0 || var.uploads_expiry_days >= 30
    error_message = "uploads_expiry_days must be 0 (off) or at least 30 (the app's retention window)."
  }
}

variable "noncurrent_version_days" {
  description = "Days a deleted or overwritten object version is kept (recovery window after a purge or a regeneration) before S3 removes it for good."
  type        = number
  default     = 7

  validation {
    condition     = var.noncurrent_version_days >= 1 && var.noncurrent_version_days <= 30
    error_message = "noncurrent_version_days must be between 1 and 30: purged customer data must not linger."
  }
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
