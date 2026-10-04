# ---------------------------------------------------------------------------
# Module: ecs-app — the MoDocument service on ECS/EC2
#
# Gate / control mapping:
#   SUP-S04       Decision (2 Oct 2026): ECS on EC2, not Fargate — LibreOffice
#                 + OCR workloads want predictable CPU and an instance cache.
#   SEC-002/003   Service autoscaling: target tracking on CPU with a hard max
#                 cap, so a traffic spike (or an LLM-cost attack) scales out
#                 bounded, never unbounded.
#   SEC-018       Secrets ONLY from SSM Parameter Store / Secrets Manager:
#                 MODOCUMENT_JWT_SECRET, MODOCUMENT_MFA_KEY, DB credentials.
#                 Nothing secret in the task definition JSON, tfvars or state.
#   SEC-028       ALB listener pinned to a TLS 1.2+ security policy. HSTS is
#                 emitted by the APP (SecurityConfig) when X-Forwarded-Proto
#                 is https — the ALB must keep forwarding that header (it
#                 does by default); do not strip it.
#   SEC-015      Instances are Session-Manager-only: no SSH port, no key pair
#                 (the security-monitoring module provides session logging).
#
# Images run as NON-ROOT: the repo's Dockerfile builds a non-root runtime
# image and the frontend is nginx:alpine; keep user remapping intact when
# mirroring images into ECR. The container pair mirrors docker-compose.yml —
# frontend nginx is the ONLY published entry (trusted-proxy model: it
# overwrites X-Forwarded-*), the app listens on 8080 reachable only inside
# the task.
#
# NOT APPLIED — `terraform validate` pending a machine with terraform.
# ---------------------------------------------------------------------------

data "aws_ssm_parameter" "ecs_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"
}

# --- Cluster + EC2 capacity -------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = var.tags
}

resource "aws_iam_role" "instance" {
  name_prefix = "${var.name}-ecs-node-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "instance_ecs" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

# SEC-015: Session Manager instead of SSH (no inbound port, every session
# logged by the security-monitoring module).
resource "aws_iam_role_policy_attachment" "instance_ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "this" {
  name_prefix = "${var.name}-ecs-node-"
  role        = aws_iam_role.instance.name
}

resource "aws_launch_template" "this" {
  name_prefix   = "${var.name}-ecs-"
  image_id      = data.aws_ssm_parameter.ecs_ami.value
  instance_type = var.instance_type
  # NO key_name: Session Manager only (SEC-015).

  iam_instance_profile {
    name = aws_iam_instance_profile.this.name
  }

  vpc_security_group_ids = [var.app_security_group_id]

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 2          # containers may reach the role
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size = 60
      volume_type = "gp3"
      encrypted   = true
    }
  }

  user_data = base64encode(<<-EOT
    #!/bin/bash
    echo "ECS_CLUSTER=${var.name}" >> /etc/ecs/ecs.config
    echo "ECS_DISABLE_PRIVILEGED=true" >> /etc/ecs/ecs.config
  EOT
  )

  tag_specifications {
    resource_type = "instance"
    tags          = merge(var.tags, { Name = "${var.name}-ecs-node" })
  }
}

resource "aws_autoscaling_group" "this" {
  name_prefix         = "${var.name}-ecs-"
  vpc_zone_identifier = var.private_app_subnet_ids
  min_size            = var.min_instances
  max_size            = var.max_instances
  desired_capacity    = var.min_instances

  launch_template {
    id      = aws_launch_template.this.id
    version = "$Latest"
  }

  protect_from_scale_in = true

  tag {
    key                 = "AmazonECSManaged"
    value               = "true"
    propagate_at_launch = true
  }

  lifecycle {
    ignore_changes = [desired_capacity]
  }
}

resource "aws_ecs_capacity_provider" "this" {
  name = var.name

  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.this.arn
    managed_termination_protection = "ENABLED"

    managed_scaling {
      status                    = "ENABLED"
      target_capacity           = 85
      minimum_scaling_step_size = 1
      maximum_scaling_step_size = 2
    }
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = [aws_ecs_capacity_provider.this.name]

  default_capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.this.name
    weight            = 1
  }
}

# --- Task roles + secrets (SEC-018) ----------------------------------------

resource "aws_iam_role" "execution" {
  name_prefix = "${var.name}-ecs-exec-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Only the exact secrets the task injects — least privilege, no wildcard.
resource "aws_iam_role_policy" "execution_secrets" {
  name_prefix = "${var.name}-secrets-"
  role        = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue", "ssm:GetParameters"]
      Resource = [
        var.jwt_secret_arn,
        var.mfa_key_secret_arn,
        var.db_app_credentials_secret_arn,
        var.db_master_secret_arn,
      ]
    }]
  })
}

resource "aws_iam_role" "task" {
  name_prefix = "${var.name}-ecs-task-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}"
  retention_in_days = 400 # hot window; the 7y archive is the backups module's bucket

  tags = var.tags
}

# --- Task definition: frontend nginx (entry) + app, mirroring compose ------

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["EC2"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "frontend"
      image     = var.frontend_image
      essential = true
      portMappings = [
        { containerPort = 80, protocol = "tcp" }
      ]
      # nginx:alpine runs workers unprivileged; the image overwrites
      # X-Forwarded-* (trusted-proxy contract in frontend/nginx.conf).
      readonlyRootFilesystem = false
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "frontend"
        }
      }
    },
    {
      name      = "app"
      image     = var.app_image # repo Dockerfile: non-root runtime user
      essential = true
      portMappings = [
        { containerPort = 8080, protocol = "tcp" }
      ]
      environment = [
        { name = "MODOCUMENT_DB_URL", value = var.db_jdbc_url },
        { name = "MODOCUMENT_CORS_ALLOWED_ORIGINS", value = var.cors_allowed_origins },
        { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profiles }
      ]
      # SEC-018: secrets injected at start from Secrets Manager / SSM.
      secrets = [
        { name = "MODOCUMENT_JWT_SECRET", valueFrom = var.jwt_secret_arn },
        { name = "MODOCUMENT_MFA_KEY", valueFrom = var.mfa_key_secret_arn },
        { name = "MODOCUMENT_DB_USERNAME", valueFrom = "${var.db_app_credentials_secret_arn}:username::" },
        { name = "MODOCUMENT_DB_PASSWORD", valueFrom = "${var.db_app_credentials_secret_arn}:password::" },
        { name = "MODOCUMENT_FLYWAY_USERNAME", valueFrom = "${var.db_master_secret_arn}:username::" },
        { name = "MODOCUMENT_FLYWAY_PASSWORD", valueFrom = "${var.db_master_secret_arn}:password::" }
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 90
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "app"
        }
      }
    }
  ])

  tags = var.tags
}

# --- ALB (TLS 1.2+, SEC-028) ------------------------------------------------

resource "aws_lb" "this" {
  name_prefix        = "modoc-"
  load_balancer_type = "application"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [var.alb_security_group_id]

  drop_invalid_header_fields = true

  tags = merge(var.tags, { Name = "${var.name}-alb" })
}

resource "aws_lb_target_group" "this" {
  name_prefix = "modoc-"
  vpc_id      = var.vpc_id
  port        = 80
  protocol    = "HTTP"
  target_type = "ip"

  health_check {
    # Through nginx to the SPA; the app container has its own deep
    # /actuator/health container health check above.
    path                = "/"
    matcher             = "200-399"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  # SEC-028: TLS 1.2 minimum, 1.3 preferred, strong ciphers only.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# --- Service + autoscaling (SEC-002/003) ------------------------------------

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.min_tasks

  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.this.name
    weight            = 1
  }

  network_configuration {
    subnets          = var.private_app_subnet_ids
    security_groups  = [var.app_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "frontend"
    container_port   = 80
  }

  health_check_grace_period_seconds = 120

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  lifecycle {
    ignore_changes = [desired_count] # autoscaling owns it
  }

  tags = var.tags
}

resource "aws_appautoscaling_target" "service" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.min_tasks
  max_capacity       = var.max_tasks # SEC-002/003: hard cap
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu-target"
  service_namespace  = aws_appautoscaling_target.service.service_namespace
  resource_id        = aws_appautoscaling_target.service.resource_id
  scalable_dimension = aws_appautoscaling_target.service.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
