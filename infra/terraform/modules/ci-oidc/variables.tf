variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC provider (one per account); false if it already exists."
  type        = bool
  default     = true
}

variable "oidc_provider_arn" {
  description = "Existing GitHub OIDC provider ARN (used when create_oidc_provider=false)."
  type        = string
  default     = ""
}

variable "github_subject_patterns" {
  description = "OIDC sub claims allowed to assume the deploy role, e.g. [\"repo:guyi34super/MODOCUMENT:ref:refs/heads/main\"]."
  type        = list(string)
}

variable "ecs_cluster_arn" {
  description = "Cluster the deploy role may roll services in."
  type        = string
}

variable "passable_role_arns" {
  description = "Task/execution role ARNs the deploy role may pass to ECS (nothing else)."
  type        = list(string)
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
