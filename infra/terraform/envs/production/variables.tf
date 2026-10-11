variable "region" {
  description = "Primary region (design decision of 2 Oct 2026)."
  type        = string
  default     = "af-south-1"
}

variable "vpc_cidr" {
  description = "Production VPC CIDR."
  type        = string
  default     = "10.40.0.0/16"
}

variable "app_domain" {
  description = "Public application domain (CloudFront alias + CORS origin)."
  type        = string
}

variable "alb_certificate_arn" {
  description = "ACM certificate in af-south-1 for the ALB (origin) listener. Must cover app_domain: CloudFront forwards the viewer's Host and verifies the origin certificate against it (edge module origin request policy)."
  type        = string
}

variable "cloudfront_certificate_arn" {
  description = "ACM certificate in us-east-1 for the CloudFront viewer cert."
  type        = string
}

variable "app_image" {
  description = "Backend image URI (ECR), immutable tag."
  type        = string
}

variable "frontend_image" {
  description = "Frontend nginx image URI (ECR), immutable tag."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.m6g.large"
}

variable "max_tasks" {
  description = "ECS autoscaling hard cap (SEC-002/003)."
  type        = number
  default     = 6
}

variable "app_egress_https_cidrs" {
  description = "App SG HTTPS egress allow-list (gate D3 seam)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enforce_egress_nacl" {
  description = "Bind the D3 egress NACL (after the allow-list is signed off)."
  type        = bool
  default     = false
}

variable "egress_allowlist_cidrs" {
  description = "Signed-off egress allow-list CIDRs (gate D3)."
  type        = list(string)
  default     = []
}

variable "enforce_backup_locks" {
  description = "Apply the IRREVERSIBLE vault/Object locks (true only after the gate D6 restore drill passes)."
  type        = bool
  default     = false
}

variable "alert_email" {
  description = "Ops email for the SNS alert topic (ws18 adds the real channels)."
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

variable "platform_admin_bootstrap_enabled" {
  description = "Create the platform-admin secret pair (MODOCUMENT_PLATFORM_ADMIN_EMAILS / _PASSWORDS) and inject it into the app task. true for the v1.1.0 rollout (set both values before the deploy; an injected secret without a value stops the task from starting); false once both PLATFORM_ADMINs have changed their passwords and enrolled MFA."
  type        = bool
  default     = false
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
