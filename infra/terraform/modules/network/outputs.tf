output "vpc_id" {
  value       = aws_vpc.this.id
  description = "VPC id."
}

output "public_subnet_ids" {
  value       = [for s in aws_subnet.public : s.id]
  description = "Public (edge/ALB) subnet ids, one per AZ."
}

output "public_subnet_cidrs" {
  value       = [for s in aws_subnet.public : s.cidr_block]
  description = "Public (edge/ALB) subnet CIDRs: where the ALB nodes live."
}

output "private_app_subnet_ids" {
  value       = [for s in aws_subnet.private_app : s.id]
  description = "Private app (ECS) subnet ids, one per AZ."
}

output "private_db_subnet_ids" {
  value       = [for s in aws_subnet.private_db : s.id]
  description = "Private DB (RDS) subnet ids, one per AZ."
}

output "alb_security_group_id" {
  value       = aws_security_group.alb.id
  description = "Security group for the ALB."
}

output "app_security_group_id" {
  value       = aws_security_group.app.id
  description = "Security group for app tasks."
}

output "db_security_group_id" {
  value       = aws_security_group.db.id
  description = "Security group for RDS (ingress only from the app SG — SUP-S06)."
}

output "flow_log_group_name" {
  value       = aws_cloudwatch_log_group.flow_logs.name
  description = "CloudWatch log group receiving VPC flow logs."
}
