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
#                 MODOCUMENT_JWT_SECRET, MODOCUMENT_MFA_KEY,
#                 MODOCUMENT_CONNECTOR_KEY (ws26), DB credentials,
#                 and — until the first admin exists — the bootstrap-admin
#                 pair (likewise the platform-admin pair, v1.1.0). Nothing
#                 secret in the task definition JSON, tfvars or state.
#   SEC-028       ALB listener pinned to a TLS 1.2+ security policy. HSTS is
#                 emitted by the APP (SecurityConfig) when X-Forwarded-Proto
#                 is https — the ALB must keep forwarding that header (it
#                 does by default); do not strip it.
#   SEC-015      Instances are Session-Manager-only: no SSH port, no key pair
#                 (the security-monitoring module provides session logging).
#
# Images run as NON-ROOT (ws16, SUP-S04): the app image runs as uid 10001
# and the frontend is nginxinc/nginx-unprivileged (uid 101) listening on
# 8080; the task definition pins both users and, like docker-compose.yml,
# drops every Linux capability and sets no-new-privileges. The container
# pair mirrors compose — frontend nginx is the ONLY published entry
# (trusted-proxy model: it overwrites X-Forwarded-*), the app is reachable
# only inside the task. awsvpc puts both containers in ONE network namespace
# with no `app` hostname, so the app listens on var.app_port (8081, via
# SERVER_PORT) and nginx is pointed at 127.0.0.1:<app_port> through the
# image's MODOCUMENT_API_UPSTREAM template variable (frontend/Dockerfile).
#
# PROXY CHAIN (client [-> CloudFront] -> ALB -> nginx -> app): the frontend
# runs in TRUSTED-PROXY mode here (frontend/docker-entrypoint.d/
# 19-modocument-trusted-proxy.envsh). MODOCUMENT_TRUSTED_PROXY_CIDRS =
# var.trusted_proxy_cidrs (the VPC CIDR, i.e. the ALB nodes): only from those
# peers does nginx take the client address (realip, recursive
# X-Forwarded-For — or CloudFront-Viewer-Address when var.client_ip_header
# says so, production) and the ALB's X-Forwarded-Proto: https; it still
# OVERWRITES both headers towards the app, so the app keeps trusting exactly
# one hop. Per-IP throttles and audit IPs see the real client, HSTS is
# emitted and OIDC redirect URIs are built as https.
#
# DB TLS: var.db_jdbc_url carries sslmode=verify-full&sslrootcert=
# /app/certs/rds-global-bundle.pem — the AWS RDS CA bundle baked into the
# app image at build time (repo Dockerfile, rds-ca stage).
#
# RELEASES (ws24, docs/release/runbook.md): Terraform owns the task
# definition TEMPLATE (family, roles, environment, secrets, health checks,
# users/capabilities) and the first revision the service starts with
# (var.app_image / var.frontend_image = a released X.Y.Z tag). From then on
# CI owns REVISIONS: the release/deploy workflows copy the latest revision
# of the family, pin both images by digest, register it and update the
# service. The service therefore ignores task_definition (and desired_count,
# owned by autoscaling), so a `terraform apply` never rolls a release back
# to the tfvars image. A Terraform-only change (new env var or secret)
# registers a new revision here and reaches the running service with the
# next CI deploy (deploy.yml with the current version re-deploys it).
# Failed deployments roll back automatically (deployment circuit breaker).
#
# NOT APPLIED — `terraform validate` passes (see the README status); plan/apply pending.
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

locals {
  # First-boot administrator (BootstrapAdminRunner): injected only while the
  # env still passes the secret pair; read access follows the same switch.
  bootstrap_admin_secrets = var.bootstrap_admin_secret_arns == null ? [] : [
    { name = "MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL", valueFrom = var.bootstrap_admin_secret_arns.email },
    { name = "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD", valueFrom = var.bootstrap_admin_secret_arns.password },
  ]
  bootstrap_admin_secret_arns = var.bootstrap_admin_secret_arns == null ? [] : [
    var.bootstrap_admin_secret_arns.email,
    var.bootstrap_admin_secret_arns.password,
  ]
  # Platform owner (ws21, v1.1.0): the two PLATFORM_ADMIN accounts, seeded
  # once by BootstrapPlatformRunner; same on/off lifecycle as the above.
  platform_admin_secrets = var.platform_admin_secret_arns == null ? [] : [
    { name = "MODOCUMENT_PLATFORM_ADMIN_EMAILS", valueFrom = var.platform_admin_secret_arns.emails },
    { name = "MODOCUMENT_PLATFORM_ADMIN_PASSWORDS", valueFrom = var.platform_admin_secret_arns.passwords },
  ]
  platform_admin_secret_arns = var.platform_admin_secret_arns == null ? [] : [
    var.platform_admin_secret_arns.emails,
    var.platform_admin_secret_arns.passwords,
  ]
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
      Resource = concat([
        var.jwt_secret_arn,
        var.mfa_key_secret_arn,
        var.connector_key_secret_arn,
        var.db_app_credentials_secret_arn,
        var.db_master_secret_arn,
      ], local.bootstrap_admin_secret_arns, local.platform_admin_secret_arns)
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

  # ws24: keep superseded revisions ACTIVE — the service may still run one
  # (CI owns the service's revision, see the header) and the circuit
  # breaker rolls back to the last steady one. Prune old revisions out of
  # band.
  skip_destroy = true

  container_definitions = jsonencode([
    {
      name      = "frontend"
      image     = var.frontend_image
      essential = true
      # nginx-unprivileged: master + workers as uid 101 on 8080 (ws16).
      user = "101"
      # ws26: the nginx mTLS server is mapped only when the mTLS listener
      # exists (mtls.tf).
      portMappings = concat([
        { containerPort = var.frontend_port, protocol = "tcp" }
        ], var.api_mtls_enabled ? [
        { containerPort = var.frontend_mtls_port, protocol = "tcp" }
      ] : [])
      environment = [
        # Shared awsvpc namespace: the app is on loopback, not `app:8080`.
        { name = "MODOCUMENT_API_UPSTREAM", value = "127.0.0.1:${var.app_port}" },
        # Trusted-proxy mode (see the header): the ALB may report the client
        # and the scheme; anyone else is this hop's own connection.
        { name = "MODOCUMENT_TRUSTED_PROXY_CIDRS", value = join(",", var.trusted_proxy_cidrs) },
        { name = "MODOCUMENT_CLIENT_IP_HEADER", value = var.client_ip_header },
        # Sent to the app as Host / X-Forwarded-Host instead of the client's
        # Host header (OIDC redirect URIs: https://<public_host>/…).
        { name = "MODOCUMENT_PUBLIC_HOST", value = var.public_host }
      ]
      # Start once the app answers its health check (compose: depends_on
      # service_healthy).
      dependsOn = [
        { containerName = "app", condition = "HEALTHY" }
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS -o /dev/null http://localhost:${var.frontend_port}/ || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 15
      }
      linuxParameters = {
        capabilities = { drop = ["ALL"] }
      }
      dockerSecurityOptions = ["no-new-privileges"]
      # The image overwrites X-Forwarded-* (trusted-proxy contract in
      # frontend/nginx.conf). Not read-only: the entrypoint renders the
      # nginx config template (and, in trusted-proxy mode, the realip
      # include) into /etc/nginx/conf.d at start; invalid
      # MODOCUMENT_TRUSTED_PROXY_CIDRS stops the container.
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
      image     = var.app_image # repo Dockerfile: USER 10001:10001
      essential = true
      user      = "10001:10001"
      portMappings = [
        { containerPort = var.app_port, protocol = "tcp" }
      ]
      linuxParameters = {
        capabilities = { drop = ["ALL"] }
      }
      dockerSecurityOptions = ["no-new-privileges"]
      environment = [
        # 8080 belongs to the frontend nginx in the shared namespace.
        { name = "SERVER_PORT", value = tostring(var.app_port) },
        { name = "MODOCUMENT_DB_URL", value = var.db_jdbc_url },
        { name = "MODOCUMENT_CORS_ALLOWED_ORIGINS", value = var.cors_allowed_origins },
        { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profiles },
        # ws24: reported by GET /api/system/version ("environment").
        { name = "MODOCUMENT_DEPLOY_ENVIRONMENT", value = var.deploy_environment },
        # ws26: who may vouch for a client certificate (the ALB subnets) —
        # empty unless the mTLS listener exists (mtls.tf), so the headers are
        # never trusted otherwise.
        # The app's TCP peer is the nginx container on loopback (awsvpc); nginx
        # itself only forwards the certificate headers from the trusted ALB.
        { name = "MODOCUMENT_MTLS_TRUSTED_PROXY_CIDRS", value = var.api_mtls_enabled ? "127.0.0.1/32" : "" },
        # ws20 guardrails: in a deployed environment the app refuses to boot
        # when a configured default lies outside its hard limits
        # (SettingsStartupCheck). Never false outside local development.
        { name = "MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS", value = "true" }
      ]
      # SEC-018: secrets injected at start from Secrets Manager / SSM. Every
      # referenced secret must HAVE a value or the task does not start.
      secrets = concat([
        { name = "MODOCUMENT_JWT_SECRET", valueFrom = var.jwt_secret_arn },
        { name = "MODOCUMENT_MFA_KEY", valueFrom = var.mfa_key_secret_arn },
        # ws25/26: connector SecretVault key (bank integration credentials).
        { name = "MODOCUMENT_CONNECTOR_KEY", valueFrom = var.connector_key_secret_arn },
        { name = "MODOCUMENT_DB_USERNAME", valueFrom = "${var.db_app_credentials_secret_arn}:username::" },
        { name = "MODOCUMENT_DB_PASSWORD", valueFrom = "${var.db_app_credentials_secret_arn}:password::" },
        { name = "MODOCUMENT_FLYWAY_USERNAME", valueFrom = "${var.db_master_secret_arn}:username::" },
        { name = "MODOCUMENT_FLYWAY_PASSWORD", valueFrom = "${var.db_master_secret_arn}:password::" }
      ], local.bootstrap_admin_secrets, local.platform_admin_secrets)
      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://localhost:${var.app_port}/actuator/health || exit 1"]
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
  port        = var.frontend_port # nginx-unprivileged (ws16)
  protocol    = "HTTP"
  target_type = "ip"

  health_check {
    # Through nginx to the SPA; the app container has its own deep
    # /actuator/health container health check above.
    path                = "/"
    port                = "traffic-port" # = var.frontend_port (8080)
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
    container_port   = var.frontend_port
  }

  # ws26: the mTLS API listener's target group (mtls.tf), when enabled.
  dynamic "load_balancer" {
    for_each = aws_lb_target_group.api_mtls
    content {
      target_group_arn = load_balancer.value.arn
      container_name   = "frontend"
      container_port   = var.frontend_mtls_port
    }
  }

  health_check_grace_period_seconds = 120

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # ws24: a deployment whose new tasks keep failing to start or to pass
  # health checks is stopped and the service returns to the last revision
  # that reached a steady state. The schema stays migrated — hence the
  # expand/contract rule (docs/release/migration-policy.md).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    # desired_count: autoscaling owns it. task_definition: CI owns the
    # revision the service runs (header "RELEASES"); Terraform only seeds
    # the first one.
    ignore_changes = [desired_count, task_definition]
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
