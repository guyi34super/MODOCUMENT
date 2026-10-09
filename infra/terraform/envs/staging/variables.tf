variable "region" {
  description = "Region (same as production for honest parity)."
  type        = string
  default     = "af-south-1"
}

variable "vpc_cidr" {
  description = "Staging VPC CIDR (distinct from production's 10.40/16)."
  type        = string
  default     = "10.41.0.0/16"
}

variable "app_domain" {
  description = "Staging domain (CORS origin + cert subject)."
  type        = string
}

variable "alb_certificate_arn" {
  description = "ACM certificate in af-south-1 for the staging ALB."
  type        = string
}

variable "app_image" {
  description = "Backend image URI."
  type        = string
}

variable "frontend_image" {
  description = "Frontend image URI."
  type        = string
}

variable "db_instance_class" {
  description = "RDS class (smaller than production)."
  type        = string
  default     = "db.t4g.medium"
}

variable "instance_type" {
  description = "ECS node type."
  type        = string
  default     = "m6i.large"
}

variable "ingress_cidrs" {
  description = "CIDRs allowed to reach the staging ALB (office/VPN; 0.0.0.0/0 only during the external pen test E4)."
  type        = list(string)
  default     = []
}

variable "alert_email" {
  description = "Ops email for staging alerts."
  type        = string
  default     = null
}

variable "github_repository" {
  description = "owner/repo allowed to deploy via OIDC."
  type        = string
  default     = "guyi34super/MODOCUMENT"
}

variable "create_oidc_provider" {
  description = "Create the account's GitHub OIDC provider here."
  type        = bool
  default     = true
}

variable "oidc_provider_arn" {
  description = "Existing provider ARN when create_oidc_provider=false."
  type        = string
  default     = ""
}

variable "bootstrap_admin_enabled" {
  description = "Create the bootstrap-admin secret pair and inject it into the app task. true for the first deploy (an empty database refuses to start without it); false once the first admin has signed in, changed the password and enrolled MFA (the secrets are then scheduled for deletion and no longer readable by the task)."
  type        = bool
  default     = true
}
