variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "region" {
  description = "AWS region (the design decision of 2 Oct 2026 is af-south-1)."
  type        = string
  default     = "af-south-1"
}

variable "vpc_cidr" {
  description = "VPC CIDR; /16 recommended so cidrsubnet(…, 4, n) yields /20 subnets."
  type        = string
  default     = "10.40.0.0/16"
}

variable "app_port" {
  description = "Port the app tasks listen on (frontend nginx container)."
  type        = number
  default     = 80
}

variable "app_egress_https_cidrs" {
  description = <<-EOT
    HTTPS egress allow-list for app tasks (gate D3 seam). 0.0.0.0/0 until the
    organisational egress allow-list is enforced; then narrow to the LLM
    endpoint, OIDC issuer and AWS service CIDRs (or move AWS traffic onto VPC
    endpoints and drop them from here).
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enable_interface_endpoints" {
  description = "Provision SSM/SecretsManager/ECR/Logs interface endpoints (full D3 posture; costs per-AZ-hour)."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
