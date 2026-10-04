variable "name" {
  description = "Environment-qualified name prefix (e.g. modocument-prod)."
  type        = string
}

variable "vpc_id" {
  description = "VPC id (for the NACL stub)."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR (NACL return-traffic rule)."
  type        = string
}

variable "private_app_subnet_ids" {
  description = "App subnets the egress NACL would bind to."
  type        = list(string)
}

variable "alb_dns_name" {
  description = "ALB DNS name — the CloudFront origin."
  type        = string
}

variable "alb_security_group_id" {
  description = "ALB SG that gets the CloudFront-prefix-list ingress rule."
  type        = string
}

variable "alb_https_listener_arn" {
  description = "ALB HTTPS listener to pin to the origin-verify header."
  type        = string
}

variable "alb_target_group_arn" {
  description = "Target group forwarded to when the origin-verify header matches."
  type        = string
}

variable "domain_aliases" {
  description = "Public domain names on the distribution (e.g. app.modocument.example)."
  type        = list(string)
  default     = []
}

variable "cloudfront_certificate_arn" {
  description = "ACM certificate ARN in us-east-1 for the CloudFront viewer cert."
  type        = string
}

variable "rate_limit_per_5min" {
  description = "WAF blanket per-IP request cap per 5 minutes (SEC-002)."
  type        = number
  default     = 2000
}

variable "auth_rate_limit_per_5min" {
  description = "WAF per-IP cap on /api/auth/* per 5 minutes (credential stuffing)."
  type        = number
  default     = 100
}

variable "enforce_egress_nacl" {
  description = "Bind the D3 egress-allow-list NACL to the app subnets (only after the organisational allow-list is signed off)."
  type        = bool
  default     = false
}

variable "egress_allowlist_cidrs" {
  description = "The signed-off egress allow-list (gate D3): CIDRs the app subnets may reach on 443."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
