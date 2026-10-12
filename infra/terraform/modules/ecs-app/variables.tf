variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "region" {
  description = "AWS region for log configuration."
  type        = string
  default     = "af-south-1"
}

variable "vpc_id" {
  description = "VPC id (network module output)."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets for the ALB."
  type        = list(string)
}

variable "private_app_subnet_ids" {
  description = "Private app subnets for ECS tasks and instances."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "ALB security group."
  type        = string
}

variable "app_security_group_id" {
  description = "App task/instance security group."
  type        = string
}

variable "app_image" {
  description = "ECR URI of the backend image (repo Dockerfile, non-root runtime)."
  type        = string
}

variable "frontend_image" {
  description = "ECR URI of the frontend nginx image (frontend/Dockerfile)."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the ALB HTTPS listener (register it in docs/security/key-certificate-register.md)."
  type        = string
}

variable "db_jdbc_url" {
  description = "jdbc:postgresql URL to the RDS endpoint. Production form: ?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem (the AWS RDS CA bundle baked into the app image — repo Dockerfile, rds-ca stage)."
  type        = string
}

variable "jwt_secret_arn" {
  description = "Secrets Manager/SSM ARN of MODOCUMENT_JWT_SECRET (SEC-018)."
  type        = string
}

variable "mfa_key_secret_arn" {
  description = "Secrets Manager/SSM ARN of MODOCUMENT_MFA_KEY (SEC-018)."
  type        = string
}

variable "connector_key_secret_arn" {
  description = "Secrets Manager/SSM ARN of MODOCUMENT_CONNECTOR_KEY (ws25/26): the AES-256-GCM key of the connector SecretVault that seals bank integration credentials at rest — separate from the MFA key (SEC-018)."
  type        = string
}

variable "api_mtls_enabled" {
  description = "ws26: serve api_domain on a second HTTPS listener with mutual TLS (verify mode, trust store in S3) for machine clients — see mtls.tf. Off by default."
  type        = bool
  default     = false
}

variable "api_domain" {
  description = "Machine API host name (api.<domain>) served by the mTLS listener."
  type        = string
  default     = ""
}

variable "api_certificate_arn" {
  description = "ACM certificate for api_domain on the mTLS listener."
  type        = string
  default     = ""
}

variable "api_mtls_listener_port" {
  description = "ALB port of the mTLS listener (mTLS applies to a whole listener, so it cannot share 443 with browsers)."
  type        = number
  default     = 8443

  validation {
    condition     = var.api_mtls_listener_port != 443 && var.api_mtls_listener_port != 80
    error_message = "api_mtls_listener_port must not be 80 or 443 (the browser listeners)."
  }
}

variable "api_mtls_ca_bundle_pem" {
  description = "PEM bundle of the CA certificates whose client certificates the mTLS listener accepts (the banks' issuing CAs; public material). Uploaded to the trust-store bucket."
  type        = string
  default     = ""
}

variable "api_mtls_client_cidrs" {
  description = "Networks allowed to reach the mTLS listener (the banks' egress addresses). Required when api_mtls_enabled."
  type        = list(string)
  default     = []
}

variable "frontend_mtls_port" {
  description = "Port of the frontend nginx's mTLS server (frontend/nginx.conf: the only server that passes X-Amzn-Mtls-Clientcert-* to the app, and only from the trusted ALB). The mTLS target group points at it."
  type        = number
  default     = 8443
}

variable "db_app_credentials_secret_arn" {
  description = "Secrets Manager ARN of the modocument_app (NOBYPASSRLS) credentials JSON {username,password}."
  type        = string
}

variable "db_master_secret_arn" {
  description = "Secrets Manager ARN of the Flyway/schema-owner credentials (the RDS-managed master secret)."
  type        = string
}

variable "bootstrap_admin_secret_arns" {
  description = "Secrets Manager ARNs of MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD ({email, password}). The app refuses its first boot on an empty AppUsers table without them; once the first admin exists the runner is a no-op. null = not injected (after the first admin has enrolled MFA — infra/terraform/README.md)."
  type = object({
    email    = string
    password = string
  })
  default = null
}

variable "platform_admin_secret_arns" {
  description = "Secrets Manager ARNs of MODOCUMENT_PLATFORM_ADMIN_EMAILS / _PASSWORDS ({emails, passwords}; two comma-separated entries each, same order). BootstrapPlatformRunner seeds the platform bank's two PLATFORM_ADMIN accounts once, while it has none; then the values are ignored. null = not injected (the default, and again after both platform admins have enrolled MFA — docs/release/runbook.md)."
  type = object({
    emails    = string
    passwords = string
  })
  default = null
}

variable "trusted_proxy_cidrs" {
  description = "Peers the frontend nginx trusts to report the client (MODOCUMENT_TRUSTED_PROXY_CIDRS): the ALB, i.e. the VPC CIDR the envs pass. Only these may set X-Forwarded-For / X-Forwarded-Proto (and CloudFront-Viewer-Address); the app SG admits nothing but the ALB SG on the frontend port anyway."
  type        = list(string)

  validation {
    condition = length(var.trusted_proxy_cidrs) > 0 && alltrue([
      for c in var.trusted_proxy_cidrs : can(cidrhost(c, 0)) && try(tonumber(split("/", c)[1]) > 0, false)
    ])
    error_message = "trusted_proxy_cidrs needs at least one IPv4/IPv6 CIDR (e.g. the VPC CIDR) and no /0: trusting every address would let any client choose its own IP."
  }
}

variable "public_host" {
  description = "Host name users reach the app on (the app domain). The frontend nginx sends it to the app as Host / X-Forwarded-Host (MODOCUMENT_PUBLIC_HOST) instead of the client's Host header, so OIDC redirect URIs and other absolute URLs always use it."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?([.][A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*(:[1-9][0-9]{0,4})?$", var.public_host))
    error_message = "public_host must be a DNS name with an optional :port, e.g. app.example.com."
  }
}

variable "client_ip_header" {
  description = "Where the frontend nginx takes the client address from when the peer is a trusted proxy (MODOCUMENT_CLIENT_IP_HEADER): X-Forwarded-For (ALB is the first hop — staging) or CloudFront-Viewer-Address (client -> CloudFront -> ALB — production; needs the edge module's origin request policy)."
  type        = string
  default     = "X-Forwarded-For"

  validation {
    condition     = contains(["X-Forwarded-For", "CloudFront-Viewer-Address"], var.client_ip_header)
    error_message = "client_ip_header must be X-Forwarded-For or CloudFront-Viewer-Address."
  }
}

variable "cors_allowed_origins" {
  description = "Value for MODOCUMENT_CORS_ALLOWED_ORIGINS (the deployed frontend origin)."
  type        = string
}

variable "deploy_environment" {
  description = "Deployment name passed to the app as MODOCUMENT_DEPLOY_ENVIRONMENT and reported by GET /api/system/version (ws24): staging or production."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.deploy_environment)
    error_message = "deploy_environment must be staging or production."
  }
}

variable "spring_profiles" {
  description = "SPRING_PROFILES_ACTIVE for the task (empty = production defaults)."
  type        = string
  default     = ""
}

variable "frontend_port" {
  description = "Port the frontend nginx container listens on: nginxinc/nginx-unprivileged binds 8080 as uid 101 (frontend/nginx.conf `listen 8080`, ws16 SUP-S04). The ALB target group, the service load_balancer block and the network module's app_port must all use this value."
  type        = number
  default     = 8080
}

variable "app_port" {
  description = "Port the Spring Boot container listens on inside the task (passed as SERVER_PORT). In awsvpc mode all containers of a task share ONE network namespace, so it must differ from frontend_port; nginx reaches it at 127.0.0.1:<app_port> (MODOCUMENT_API_UPSTREAM). Never exposed outside the task."
  type        = number
  default     = 8081

  validation {
    condition     = var.app_port != 8080
    error_message = "app_port must not be 8080: the unprivileged frontend nginx already listens there in the shared awsvpc namespace."
  }
}

variable "instance_type" {
  description = "EC2 instance type for the ECS capacity ASG (SUP-S04: EC2, not Fargate)."
  type        = string
  default     = "m6i.large"
}

variable "min_instances" {
  description = "ASG minimum instances."
  type        = number
  default     = 2
}

variable "max_instances" {
  description = "ASG maximum instances (hard cap)."
  type        = number
  default     = 4
}

variable "task_cpu" {
  description = "Task CPU units. ws29: 1024 (was 2048) so two tasks share an m6i.large (2048 units) — twice the tasks for the same nodes; the JVM sizes itself from the container limit (docker-entrypoint.sh)."
  type        = string
  default     = "1024"

  validation {
    condition     = contains(["256", "512", "1024", "2048", "4096", "8192"], var.task_cpu)
    error_message = "task_cpu must be one of 256, 512, 1024, 2048, 4096, 8192."
  }
}

variable "task_memory" {
  description = "Task memory (MiB). ws29: 3072 (was 6144): two tasks fit in an m6i.large's ~7.4 GiB ECS-registered memory."
  type        = string
  default     = "3072"

  validation {
    condition     = can(tonumber(var.task_memory)) && tonumber(var.task_memory) >= 1024
    error_message = "task_memory must be a number of MiB, at least 1024."
  }
}

variable "app_cpu" {
  description = "CPU units reserved for the app container (of task_cpu)."
  type        = number
  default     = 896
}

variable "app_memory" {
  description = "Hard memory limit of the app container (MiB, of task_memory). The JVM heap is 70% of it (MaxRAMPercentage); the rest is metaspace, threads and the soffice/tesseract children."
  type        = number
  default     = 2816

  validation {
    condition     = var.app_memory >= 1024
    error_message = "app_memory must be at least 1024 MiB (JVM + LibreOffice conversions)."
  }
}

variable "frontend_cpu" {
  description = "CPU units reserved for the frontend nginx container (of task_cpu)."
  type        = number
  default     = 128
}

variable "frontend_memory" {
  description = "Hard memory limit of the frontend nginx container (MiB, of task_memory)."
  type        = number
  default     = 256

  validation {
    condition     = var.frontend_memory >= 64
    error_message = "frontend_memory must be at least 64 MiB."
  }
}

variable "log_retention_days" {
  description = "ws29: CloudWatch retention of the app/frontend log group (days); was 400. The audit trail lives in the database and the locked archive, not here."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a CloudWatch Logs retention value (1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, ...)."
  }
}

variable "file_store" {
  description = "ws29: the S3 file store (file-store module outputs). Set: the app runs with MODOCUMENT_STORAGE_BACKEND=s3 on this bucket/key and the task role may use exactly them. null: files stay on the container disk (lost on task replacement — not for production)."
  type = object({
    bucket_name = string
    bucket_arn  = string
    kms_key_arn = string
  })
  default = null
}

variable "min_tasks" {
  description = "Service minimum task count (2 = AZ-redundant, SEC-005 availability)."
  type        = number
  default     = 2
}

variable "max_tasks" {
  description = "Service maximum task count — the SEC-002/003 cost/abuse cap. Must be placeable: <= max_instances x tasks per node (2 for the default task size on m6i.large; checked at plan)."
  type        = number
  default     = 8

  validation {
    condition     = var.max_tasks >= 1
    error_message = "max_tasks must be at least 1."
  }
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
