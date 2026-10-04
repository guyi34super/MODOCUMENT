variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "db_subnet_ids" {
  description = "Private DB subnet ids (network module output)."
  type        = list(string)
}

variable "db_security_group_id" {
  description = "DB security group (ingress only from the app SG — SUP-S06)."
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL major.minor (compose runs postgres:17)."
  type        = string
  default     = "17.4"
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.m6g.large"
}

variable "master_username" {
  description = "Master (schema-owner/Flyway) username. The runtime modocument_app role is created post-apply — see main.tf header."
  type        = string
  default     = "modocument"
}

variable "allocated_storage_gb" {
  description = "Initial storage (GB)."
  type        = number
  default     = 100
}

variable "max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling (GB)."
  type        = number
  default     = 500
}

variable "backup_retention_days" {
  description = "RDS automated backup/PITR window in days (AWS max 35; the 90-day requirement D4/PRIV-006 is met by the backups module's vault copies)."
  type        = number
  default     = 35
}

variable "deletion_protection" {
  description = "Protect the instance from terraform destroy (true in production)."
  type        = bool
  default     = true
}

variable "backup_selection_tag" {
  description = "Value of the Backup tag the backups module selects on."
  type        = string
  default     = "modocument"
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
