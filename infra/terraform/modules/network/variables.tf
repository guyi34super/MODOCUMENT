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
  description = "Port the app tasks accept ALB traffic on: the frontend nginx container, nginx-unprivileged on 8080 (ws16) — must equal the ecs-app module's frontend_port."
  type        = number
  default     = 8080
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

variable "bank_egress_cidrs" {
  description = <<-EOT
    ws26 bank integration hub, DIRECT mode: bank networks (reached over a
    per-bank VPN/PrivateLink, see the root README "implement later" list)
    that app tasks may open DATABASE (1433 SQL Server, 1521 Oracle, 3306
    MariaDB/MySQL, 5432 PostgreSQL) and SFTP (22) connections to. Empty (the
    default) = no such egress rule exists. The app additionally allows a
    private destination only when the bank's BankEgressRule is approved
    (platform maker/checker): this list is the network-level ceiling.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for c in var.bank_egress_cidrs : can(cidrhost(c, 0)) && try(tonumber(split("/", c)[1]) > 0, false)
    ])
    error_message = "bank_egress_cidrs entries must be CIDRs, never /0."
  }
}

variable "nat_gateway_count" {
  description = "ws29: NAT gateways — 2 (one per AZ, production: AZ-independent egress) or 1 (shared, staging: about half the NAT cost, egress depends on one AZ)."
  type        = number
  default     = 2

  validation {
    condition     = contains([1, 2], var.nat_gateway_count)
    error_message = "nat_gateway_count must be 1 or 2 (the VPC spans 2 AZs)."
  }
}

variable "flow_log_retention_days" {
  description = "ws29: CloudWatch retention of the VPC flow logs (days)."
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.flow_log_retention_days)
    error_message = "flow_log_retention_days must be a CloudWatch Logs retention value (1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, ...)."
  }
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
