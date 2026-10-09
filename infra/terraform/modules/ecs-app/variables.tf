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
  description = "jdbc:postgresql URL to the RDS endpoint."
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
