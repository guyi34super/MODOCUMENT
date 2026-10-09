# ---------------------------------------------------------------------------
# Module: ci-oidc — GitHub Actions OIDC federation + per-env deploy roles
#
# Gate / control mapping:
#   SUP-S05 / A8   No static cloud keys anywhere: GitHub Actions assumes a
#                  short-lived role via the GitHub OIDC provider. One role
#                  per environment, trust pinned to THIS repository and (for
#                  production) to the protected branch / environment, so a
#                  fork or feature branch can never deploy.
#
# Least privilege: the deploy role may push images to the two ECR repos and
# roll the one ECS service. It may NOT touch IAM, KMS, the backup vaults, the
# log archive or CloudTrail — those change only through a human-applied
# Terraform run (until a dedicated plan/apply pipeline with its own reviewed
# role exists).
#
# NOT APPLIED — `terraform validate` pending a machine with terraform.
# ---------------------------------------------------------------------------

# One provider per account. If the account already has it (it is a singleton
# by URL), set var.create_oidc_provider=false and pass its ARN.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # AWS validates GitHub's cert chain against trusted CAs; the thumbprint is
  # retained for API compatibility.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = var.tags
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.oidc_provider_arn
}

resource "aws_iam_role" "deploy" {
  name = "${var.name}-github-deploy"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          # e.g. "repo:guyi34super/MODOCUMENT:ref:refs/heads/main" for
          # production, "repo:guyi34super/MODOCUMENT:*" for staging.
          "token.actions.githubusercontent.com:sub" = var.github_subject_patterns
        }
      }
    }]
  })

  max_session_duration = 3600

  tags = var.tags
}

resource "aws_ecr_repository" "app" {
  name                 = "${var.name}/app"
  image_tag_mutability = "IMMUTABLE" # a deployed tag can never be silently replaced

  image_scanning_configuration {
    scan_on_push = true # supply-chain input (SEC-040 threat matrix)
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = var.tags
}

resource "aws_ecr_repository" "frontend" {
  name                 = "${var.name}/frontend"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = var.tags
}

resource "aws_iam_role_policy" "deploy" {
  name_prefix = "${var.name}-deploy-"
  role        = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
        ]
        Resource = [
          aws_ecr_repository.app.arn,
          aws_ecr_repository.frontend.arn,
        ]
      },
      {
        Sid    = "EcsRollingDeploy"
        Effect = "Allow"
        Action = [
          "ecs:DescribeServices",
          "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition",
          "ecs:UpdateService",
        ]
        Resource = "*"
        Condition = {
          # Deploy only inside this environment's cluster.
          StringEqualsIfExists = { "ecs:cluster" = var.ecs_cluster_arn }
        }
      },
      {
        Sid      = "PassOnlyTaskRoles"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = var.passable_role_arns
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      }
    ]
  })
}
