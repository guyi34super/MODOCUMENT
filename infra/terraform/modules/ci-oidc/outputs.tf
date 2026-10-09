output "deploy_role_arn" {
  value       = aws_iam_role.deploy.arn
  description = "Role GitHub Actions assumes via OIDC (configure-aws-credentials role-to-assume)."
}

output "app_repository_url" {
  value       = aws_ecr_repository.app.repository_url
  description = "ECR URL for the backend image."
}

output "frontend_repository_url" {
  value       = aws_ecr_repository.frontend.repository_url
  description = "ECR URL for the frontend image."
}
