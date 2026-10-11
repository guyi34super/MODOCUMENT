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
  description = "OIDC sub claims (StringLike patterns) allowed to assume the deploy role, e.g. [\"repo:guyi34super/MODOCUMENT:environment:production\"]."
  type        = list(string)

  validation {
    condition = length(var.github_subject_patterns) > 0 && alltrue([
      for s in var.github_subject_patterns : can(regex("^repo:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:.+$", s))
    ])
    error_message = "Every subject must be pinned to one repository: repo:<owner>/<repo>:<ref|environment|*>. A wildcard owner or repository would let other repositories deploy."
  }
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
