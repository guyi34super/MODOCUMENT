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

# --- ws26 bank integration hub ------------------------------------------------

variable "bank_egress_cidrs" {
  description = "Bank networks app tasks may reach for DIRECT-mode DATABASE (1433/1521/3306/5432) and SFTP (22) connections (network module). Empty = no such egress."
  type        = list(string)
  default     = []
}

variable "api_mtls_enabled" {
  description = "Serve api_domain on the mutual-TLS listener for machine clients (ecs-app mtls.tf). Off by default."
  type        = bool
  default     = false
}

variable "api_domain" {
  description = "Machine API host name, e.g. api.<app domain>, pointed at the ALB."
  type        = string
  default     = ""
}

variable "api_certificate_arn" {
  description = "ACM certificate (this region) covering api_domain."
  type        = string
  default     = ""
}

variable "api_mtls_ca_bundle_pem" {
  description = "PEM bundle of the banks' client-certificate CAs for the mTLS trust store (public material)."
  type        = string
  default     = ""
}

variable "api_mtls_client_cidrs" {
  description = "Bank egress networks allowed to reach the mTLS listener."
  type        = list(string)
  default     = []
}
