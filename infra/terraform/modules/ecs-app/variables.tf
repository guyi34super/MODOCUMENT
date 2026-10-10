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
  description = "Task CPU units."
  type        = string
  default     = "2048"
}

variable "task_memory" {
  description = "Task memory (MiB)."
  type        = string
  default     = "6144"
}

variable "min_tasks" {
  description = "Service minimum task count (2 = AZ-redundant, SEC-005 availability)."
  type        = number
  default     = 2
}

variable "max_tasks" {
  description = "Service maximum task count — the SEC-002/003 cost/abuse cap."
  type        = number
  default     = 6
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
