output "alb_arn" {
  value       = aws_lb.this.arn
  description = "ALB ARN (the edge module attaches the regional WAF here when CloudFront is bypassed in staging)."
}

output "alb_arn_suffix" {
  value       = aws_lb.this.arn_suffix
  description = "ALB arn_suffix for CloudWatch alarm dimensions."
}

output "alb_https_listener_arn" {
  value       = aws_lb_listener.https.arn
  description = "HTTPS listener ARN (the edge module pins it to the origin-verify header)."
}

output "target_group_arn" {
  value       = aws_lb_target_group.this.arn
  description = "Target group ARN."
}

output "task_role_arns" {
  value       = [aws_iam_role.task.arn, aws_iam_role.execution.arn]
  description = "Roles the CI deploy role may iam:PassRole to ECS (ci-oidc input)."
}

output "alb_dns_name" {
  value       = aws_lb.this.dns_name
  description = "ALB DNS name — the CloudFront origin (edge module)."
}

output "alb_zone_id" {
  value       = aws_lb.this.zone_id
  description = "ALB hosted zone id for alias records."
}

output "cluster_arn" {
  value       = aws_ecs_cluster.this.arn
  description = "ECS cluster ARN (ci-oidc deploy-role condition)."
}

output "cluster_name" {
  value       = aws_ecs_cluster.this.name
  description = "ECS cluster name."
}

output "service_name" {
  value       = aws_ecs_service.this.name
  description = "ECS service name (alarm dimension for security-monitoring)."
}

output "api_mtls_listener_arn" {
  value       = one(aws_lb_listener.api_mtls[*].arn)
  description = "ws26 mTLS API listener ARN (null while api_mtls_enabled is false). Point api.<domain> at alb_dns_name."
}

output "log_group_name" {
  value       = aws_cloudwatch_log_group.app.name
  description = "App/frontend CloudWatch log group (metric-filter seam for ws18 alerts)."
}
