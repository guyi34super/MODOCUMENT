variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "trail_bucket" {
  description = "Bucket name receiving CloudTrail + SSM session logs. Target state (A6): the lock account's bucket; interim: the backups module's local Object-Lock bucket."
  type        = string
}

variable "trail_bucket_arn" {
  description = "ARN of the trail bucket."
  type        = string
}

variable "manage_trail_bucket_policy" {
  description = "Attach the CloudTrail service bucket policy here (true while the bucket is same-account; false once it moves to the lock account, which then owns the policy)."
  type        = bool
  default     = true
}

variable "alert_email" {
  description = "Optional ops email subscribed to the alert topic (ws18 wires the real channels)."
  type        = string
  default     = null
}

variable "alb_arn_suffix" {
  description = "ALB arn_suffix for CloudWatch dimensions."
  type        = string
}

variable "ecs_cluster_name" {
  description = "ECS cluster name (alarm dimension)."
  type        = string
}

variable "ecs_service_name" {
  description = "ECS service name (alarm dimension)."
  type        = string
}

variable "rds_instance_id" {
  description = "RDS instance identifier (alarm dimension)."
  type        = string
}

variable "app_log_group_name" {
  description = "App CloudWatch log group (metric filters on audit actions, e.g. ACCOUNT_LOCKED)."
  type        = string
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
