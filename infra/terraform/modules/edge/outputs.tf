output "distribution_domain_name" {
  value       = aws_cloudfront_distribution.this.domain_name
  description = "CloudFront domain — point the public DNS records here."
}

output "distribution_id" {
  value       = aws_cloudfront_distribution.this.id
  description = "CloudFront distribution id."
}

output "web_acl_arn" {
  value       = aws_wafv2_web_acl.this.arn
  description = "WAF web ACL ARN."
}
