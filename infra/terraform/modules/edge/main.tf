# ---------------------------------------------------------------------------
# Module: edge — CloudFront + AWS WAF in front of the ALB
#
# Gate / control mapping:
#   SUP-S03 / SEC-002  WAF: AWS managed rule groups (common, known bad inputs,
#                      IP reputation) + a rate rule, in front of the app's own
#                      per-IP/per-email limiters — defense in depth, the edge
#                      absorbs volumetric abuse before it costs app CPU or
#                      LLM spend.
#   A12 / D3           Origin reachable ONLY through CloudFront: the ALB SG
#                      admits the CloudFront managed prefix list plus a
#                      custom origin header verified at the listener. NACL
#                      stubs document the deny-by-default egress posture.
#   SEC-022 / SEC-028  Client identity + scheme through the chain (client ->
#                      CloudFront -> ALB -> nginx -> app): the origin request
#                      policy adds CloudFront-Viewer-Address and forwards the
#                      viewer's Host — see the policy resource below.
#
# CloudFront + its WAF (scope CLOUDFRONT) must be created in us-east-1 —
# the env wires the aws.us_east_1 aliased provider for those resources.
#
# NOT APPLIED — `terraform validate` passes (see the README status); plan/apply pending.
# ---------------------------------------------------------------------------

terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws.us_east_1]
    }
    random = {
      source = "hashicorp/random"
    }
  }
}

# --- WAF (CLOUDFRONT scope, us-east-1) --------------------------------------

resource "aws_wafv2_web_acl" "this" {
  provider = aws.us_east_1

  name  = "${var.name}-waf"
  scope = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "aws-common"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-kbi"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-iprep"
      sampled_requests_enabled   = true
    }
  }

  # SEC-002: edge rate cap per source IP (the app keeps its own finer
  # per-IP/per-email auth limits and per-user LLM quota underneath).
  rule {
    name     = "rate-cap"
    priority = 40

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_per_5min
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-rate"
      sampled_requests_enabled   = true
    }
  }

  # Tighter cap on the login/refresh endpoints (credential stuffing).
  rule {
    name     = "rate-cap-auth"
    priority = 50

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.auth_rate_limit_per_5min
        aggregate_key_type = "IP"

        scope_down_statement {
          byte_match_statement {
            search_string         = "/api/auth/"
            positional_constraint = "STARTS_WITH"

            field_to_match {
              uri_path {}
            }

            text_transformation {
              priority = 0
              type     = "LOWERCASE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-rate-auth"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-waf"
    sampled_requests_enabled   = true
  }

  tags = var.tags
}

# --- CloudFront --------------------------------------------------------------

# Secret header CloudFront stamps on every origin request; an ALB listener
# rule (below) drops requests without it, so even a caller who learns the
# ALB's DNS name cannot go around the WAF (A12/D3).
resource "random_password" "origin_verify" {
  length  = 32
  special = false
}

# Origin request policy (replaces the managed AllViewerExceptHostHeader):
#   * CloudFront-Viewer-Address — the viewer's IP:port, set by CloudFront.
#     The ALB appends the CloudFront EDGE address to X-Forwarded-For, so the
#     rightmost untrusted hop nginx could resolve is an edge node, not the
#     user; trusting CloudFront's published origin-facing ranges instead would
#     mean shipping an IP list into nginx that AWS changes at will (the
#     managed prefix list only works for security groups). The frontend
#     nginx honours this header only from the trusted ALB (VPC CIDR) and only
#     when ecs-app's client_ip_header = "CloudFront-Viewer-Address"
#     (frontend/docker-entrypoint.d/19-modocument-trusted-proxy.envsh).
#   * The viewer's Host header (all viewer headers, cookies, query strings —
#     as before, now Host too): the app builds OIDC redirect URIs and other
#     absolute URLs from X-Forwarded-Host = nginx's $host, which would
#     otherwise be the ALB's DNS name. It also makes CloudFront verify the
#     origin certificate against the app domain — the ALB's ACM certificate
#     (var alb_certificate_arn in the env) must cover var.domain_aliases;
#     with the origin's own DNS name (*.elb.amazonaws.com) as the expected
#     name no ACM certificate could ever match (CloudFront answers 502).
resource "aws_cloudfront_origin_request_policy" "this" {
  name    = "${var.name}-origin"
  comment = "All viewer values incl. Host + CloudFront-Viewer-Address (nginx trusted-proxy mode)"

  cookies_config {
    cookie_behavior = "all"
  }

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"

    headers {
      items = ["CloudFront-Viewer-Address"]
    }
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled         = true
  comment         = "${var.name} edge"
  aliases         = var.domain_aliases
  price_class     = "PriceClass_All" # af-south-1 users need the Africa POPs
  web_acl_id      = aws_wafv2_web_acl.this.arn
  is_ipv6_enabled = true

  origin {
    origin_id   = "alb"
    domain_name = var.alb_dns_name

    custom_origin_config {
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      http_port              = 80
      https_port             = 443
    }

    custom_header {
      name  = "x-modoc-origin-verify"
      value = random_password.origin_verify.result
    }
  }

  default_cache_behavior {
    target_origin_id       = "alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]

    # API + authenticated SPA: pass everything through, cache nothing by
    # default (managed CachingDisabled + the origin request policy above).
    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    origin_request_policy_id = aws_cloudfront_origin_request_policy.this.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.cloudfront_certificate_arn # must live in us-east-1
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021" # SEC-028
  }

  tags = var.tags
}

# --- Lock the ALB to CloudFront (A12/D3) ------------------------------------

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group_rule" "alb_from_cloudfront" {
  type              = "ingress"
  security_group_id = var.alb_security_group_id
  protocol          = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_ids   = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  description       = "HTTPS only from CloudFront origin-facing ranges (A12)"
}

resource "aws_lb_listener_rule" "require_origin_header" {
  listener_arn = var.alb_https_listener_arn
  priority     = 1

  action {
    type             = "forward"
    target_group_arn = var.alb_target_group_arn
  }

  condition {
    http_header {
      http_header_name = "x-modoc-origin-verify"
      values           = [random_password.origin_verify.result]
    }
  }
}

# With the rule above as priority 1, repoint the listener DEFAULT action to a
# fixed 403 so headerless requests never reach the app. Terraform cannot
# easily rewrite the default action owned by the ecs-app module, so this
# module ships it as an explicit second rule catching everything else.
resource "aws_lb_listener_rule" "deny_direct" {
  listener_arn = var.alb_https_listener_arn
  priority     = 2

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Access through the application domain only."
      status_code  = "403"
    }
  }

  condition {
    path_pattern {
      values = ["/*"]
    }
  }
}

# --- NACL stub: deny-by-default egress posture (D3) --------------------------
# The organisational egress allow-list (gate D3) is enforced in two layers:
# the app SG's named egress rules (network module) and, once the allow-list
# is signed off, subnet NACLs. The stub below is the seam — populated from
# var.egress_allowlist_cidrs when D3 is executed; until then it stays
# permissive so it cannot silently break staging.
resource "aws_network_acl" "app_egress" {
  count  = var.enforce_egress_nacl ? 1 : 0
  vpc_id = var.vpc_id

  subnet_ids = var.private_app_subnet_ids

  dynamic "egress" {
    for_each = { for i, cidr in var.egress_allowlist_cidrs : i => cidr }

    content {
      rule_no    = 100 + egress.key * 10
      protocol   = "tcp"
      action     = "allow"
      cidr_block = egress.value
      from_port  = 443
      to_port    = 443
    }
  }

  # Return traffic for inbound connections from the ALB.
  egress {
    rule_no    = 900
    protocol   = "tcp"
    action     = "allow"
    cidr_block = var.vpc_cidr
    from_port  = 1024
    to_port    = 65535
  }

  ingress {
    rule_no    = 100
    protocol   = "-1"
    action     = "allow"
    cidr_block = var.vpc_cidr
    from_port  = 0
    to_port    = 0
  }

  tags = merge(var.tags, { Name = "${var.name}-app-egress-nacl (D3)" })
}
