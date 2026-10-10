# ---------------------------------------------------------------------------
# ws26 — mutual TLS for machine clients on api.<domain> (OFF by default).
#
# Bank systems and Connector Agents that authenticate with a client
# certificate (ApiAuthMethod MTLS) reach MoDocument on var.api_domain at
# var.api_mtls_listener_port (8443 by default; one ALB listener per port, and
# mTLS verify mode applies to a whole listener, so it cannot share 443 with
# the browser listener). The ALB VERIFIES the chain against a trust store
# (the CA bundle in S3 below) and forwards the X-Amzn-Mtls-Clientcert-*
# headers to a DEDICATED target group: the frontend nginx's second server
# (port var.frontend_mtls_port, frontend/nginx.conf) — the only place that
# passes those headers to the app, and only from the trusted ALB. The
# browser path (443 → nginx 8080) always strips them, so a client cannot
# forge a certificate identity by sending the headers itself.
#
# The app maps the certificate fingerprint to an ApiClient (SE2). Per-bank
# CA certificates go into var.api_mtls_ca_bundle_pem (public material, not
# secret); a change uploads a new object version and updates the trust store.
# ---------------------------------------------------------------------------

locals {
  api_mtls = var.api_mtls_enabled ? 1 : 0
}

resource "aws_s3_bucket" "mtls_trust_store" {
  count         = local.api_mtls
  bucket_prefix = "${var.name}-mtls-"

  tags = var.tags
}

resource "aws_s3_bucket_ownership_controls" "mtls_trust_store" {
  count  = local.api_mtls
  bucket = aws_s3_bucket.mtls_trust_store[0].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "mtls_trust_store" {
  count  = local.api_mtls
  bucket = aws_s3_bucket.mtls_trust_store[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "mtls_trust_store" {
  count  = local.api_mtls
  bucket = aws_s3_bucket.mtls_trust_store[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "mtls_trust_store" {
  count  = local.api_mtls
  bucket = aws_s3_bucket.mtls_trust_store[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_object" "mtls_ca_bundle" {
  count   = local.api_mtls
  bucket  = aws_s3_bucket.mtls_trust_store[0].id
  key     = "trust-store/ca-bundle.pem"
  content = var.api_mtls_ca_bundle_pem

  content_type = "application/x-pem-file"

  depends_on = [aws_s3_bucket_versioning.mtls_trust_store]
}

resource "aws_lb_trust_store" "api" {
  count       = local.api_mtls
  name_prefix = "modoc-"

  ca_certificates_bundle_s3_bucket         = aws_s3_bucket.mtls_trust_store[0].id
  ca_certificates_bundle_s3_key            = aws_s3_object.mtls_ca_bundle[0].key
  ca_certificates_bundle_s3_object_version = aws_s3_object.mtls_ca_bundle[0].version_id

  tags = var.tags

  lifecycle {
    precondition {
      condition = (var.api_domain != "" && var.api_certificate_arn != ""
        && strcontains(var.api_mtls_ca_bundle_pem, "BEGIN CERTIFICATE")
      && length(var.api_mtls_client_cidrs) > 0)
      error_message = "api_mtls_enabled needs api_domain, api_certificate_arn, a PEM api_mtls_ca_bundle_pem and api_mtls_client_cidrs."
    }
  }
}

resource "aws_lb_target_group" "api_mtls" {
  count       = local.api_mtls
  name_prefix = "mtls-"
  vpc_id      = var.vpc_id
  port        = var.frontend_mtls_port
  protocol    = "HTTP"
  target_type = "ip"

  health_check {
    # nginx's own answer on the mTLS server (no client certificate needed).
    path                = "/nginx-health"
    port                = "traffic-port"
    matcher             = "200"
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

resource "aws_lb_listener" "api_mtls" {
  count             = local.api_mtls
  load_balancer_arn = aws_lb.this.arn
  port              = var.api_mtls_listener_port
  protocol          = "HTTPS"
  # SEC-028: same TLS policy as the browser listener.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.api_certificate_arn

  mutual_authentication {
    mode                             = "verify"
    trust_store_arn                  = aws_lb_trust_store.api[0].arn
    ignore_client_certificate_expiry = false
  }

  # Only api.<domain> is served here; anything else is a plain 404.
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "api_mtls" {
  count        = local.api_mtls
  listener_arn = aws_lb_listener.api_mtls[0].arn
  priority     = 10

  condition {
    host_header {
      values = [var.api_domain]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_mtls[0].arn
  }
}

# Clients (bank systems, agents) reach the mTLS listener directly — not
# through CloudFront, which cannot carry the client certificate.
resource "aws_security_group_rule" "alb_mtls_in" {
  count             = local.api_mtls
  type              = "ingress"
  security_group_id = var.alb_security_group_id
  protocol          = "tcp"
  from_port         = var.api_mtls_listener_port
  to_port           = var.api_mtls_listener_port
  cidr_blocks       = var.api_mtls_client_cidrs
  description       = "mTLS API listener (api domain) from bank client networks"
}

resource "aws_security_group_rule" "alb_egress_mtls" {
  count                    = local.api_mtls
  type                     = "egress"
  security_group_id        = var.alb_security_group_id
  protocol                 = "tcp"
  from_port                = var.frontend_mtls_port
  to_port                  = var.frontend_mtls_port
  source_security_group_id = var.app_security_group_id
  description              = "ALB forwards mTLS API traffic to the nginx mTLS server"
}

resource "aws_security_group_rule" "app_from_alb_mtls" {
  count                    = local.api_mtls
  type                     = "ingress"
  security_group_id        = var.app_security_group_id
  source_security_group_id = var.alb_security_group_id
  protocol                 = "tcp"
  from_port                = var.frontend_mtls_port
  to_port                  = var.frontend_mtls_port
  description              = "nginx mTLS server from the ALB only"
}
