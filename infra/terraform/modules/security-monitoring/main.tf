# ---------------------------------------------------------------------------
# Module: security-monitoring — GuardDuty, CloudTrail, alarms, Session Manager
#
# Gate / control mapping:
#   PRIV-003        GuardDuty: managed threat detection (ransomware beaconing,
#                   credential exfiltration, crypto-mining) with findings
#                   routed to the alert topic.
#   A6 / SEC-015    CloudTrail, multi-region, log-file validation ON, writing
#                   to the LOCKED log-archive bucket. The two-account model
#                   (root README) puts that bucket in the separate lock
#                   account so a compromised production admin cannot erase
#                   their tracks; until that account exists (gate A1/A6),
#                   var.trail_bucket points at the backups module's local
#                   Object-Lock bucket — still WORM, just same-account.
#   SEC-015         Session Manager: no SSH; every interactive session is
#                   recorded to the locked storage ("after go-live"
#                   commitment, ready to enable here).
#   ws18 seam       Alarms publish to one SNS topic; ws18's alert channels
#                   (email/chat webhook) subscribe to it — the topic ARN is
#                   this module's alert_topic_arn output.
#
# NOT APPLIED — `terraform validate` passes (see the README status); plan/apply pending.
# ---------------------------------------------------------------------------

data "aws_caller_identity" "current" {}

# --- Alert channel seam (ws18) ----------------------------------------------

resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-security-alerts"
  kms_master_key_id = "alias/aws/sns"

  tags = var.tags
}

# ws18 subscribes its real channels; this placeholder documents the seam.
resource "aws_sns_topic_subscription" "ops_email" {
  count = var.alert_email == null ? 0 : 1

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# --- GuardDuty (PRIV-003) -----------------------------------------------------

resource "aws_guardduty_detector" "this" {
  enable                       = true
  finding_publishing_frequency = "FIFTEEN_MINUTES"

  tags = var.tags
}

resource "aws_cloudwatch_event_rule" "guardduty_findings" {
  name        = "${var.name}-guardduty-findings"
  description = "Route GuardDuty findings (severity >= 4) to the alert topic"

  event_pattern = jsonencode({
    source      = ["aws.guardduty"]
    detail-type = ["GuardDuty Finding"]
    detail = {
      severity = [{ numeric = [">=", 4] }]
    }
  })

  tags = var.tags
}

resource "aws_cloudwatch_event_target" "guardduty_to_sns" {
  rule = aws_cloudwatch_event_rule.guardduty_findings.name
  arn  = aws_sns_topic.alerts.arn
}

resource "aws_sns_topic_policy" "allow_events" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sns:Publish"
      Resource  = aws_sns_topic.alerts.arn
    }]
  })
}

# --- CloudTrail → locked bucket (A6 / SEC-015) --------------------------------

resource "aws_cloudwatch_log_group" "trail" {
  name              = "/cloudtrail/${var.name}"
  retention_in_days = 400

  tags = var.tags
}

resource "aws_iam_role" "trail_to_cw" {
  name_prefix = "${var.name}-trail-cw-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudtrail.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy" "trail_to_cw" {
  name_prefix = "${var.name}-trail-cw-"
  role        = aws_iam_role.trail_to_cw.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = "${aws_cloudwatch_log_group.trail.arn}:*"
    }]
  })
}

# The trail bucket needs a CloudTrail service policy; when the bucket lives
# in the separate lock account (A6), apply the equivalent policy THERE and
# set var.manage_trail_bucket_policy=false here.
resource "aws_s3_bucket_policy" "trail" {
  count  = var.manage_trail_bucket_policy ? 1 : 0
  bucket = var.trail_bucket

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AWSCloudTrailAclCheck"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:GetBucketAcl"
        Resource  = var.trail_bucket_arn
      },
      {
        Sid       = "AWSCloudTrailWrite"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${var.trail_bucket_arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
        Condition = {
          StringEquals = { "s3:x-amz-acl" = "bucket-owner-full-control" }
        }
      }
    ]
  })
}

resource "aws_cloudtrail" "this" {
  name                          = "${var.name}-trail"
  s3_bucket_name                = var.trail_bucket
  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true # tamper-evidence (A6)

  cloud_watch_logs_group_arn = "${aws_cloudwatch_log_group.trail.arn}:*"
  cloud_watch_logs_role_arn  = aws_iam_role.trail_to_cw.arn

  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }

  tags = var.tags

  depends_on = [aws_s3_bucket_policy.trail]
}

# --- CloudWatch alarms → SNS ---------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${var.name}-alb-5xx"
  alarm_description   = "Backend 5xx burst (availability / SEC-005 SLA input)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 25
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${var.name}-ecs-cpu-high"
  alarm_description   = "Sustained CPU at the autoscaling max cap (SEC-003)"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"

  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_service_name
  }

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${var.name}-rds-free-storage"
  alarm_description   = "RDS free storage under 10 GiB"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 10737418240
  comparison_operator = "LessThanThreshold"

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_id
  }

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

# App-level security signal: the app logs every LOGIN_FAILED / ACCOUNT_LOCKED
# audit event; the metric filter turns lockouts into an alertable metric
# (brute-force detection, SEC-031 SIEM-reference input).
resource "aws_cloudwatch_log_metric_filter" "account_locked" {
  name           = "${var.name}-account-locked"
  log_group_name = var.app_log_group_name
  pattern        = "ACCOUNT_LOCKED"

  metric_transformation {
    name      = "AccountLocked"
    namespace = "MoDocument/Security"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "account_locked_burst" {
  alarm_name          = "${var.name}-account-locked-burst"
  alarm_description   = "Multiple account lockouts in 15 min — credential stuffing suspicion"
  namespace           = "MoDocument/Security"
  metric_name         = "AccountLocked"
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

# --- Session Manager: sessions recorded to locked storage (SEC-015) -----------

resource "aws_ssm_document" "session_preferences" {
  name            = "SSM-SessionManagerRunShell"
  document_type   = "Session"
  document_format = "JSON"

  content = jsonencode({
    schemaVersion = "1.0"
    description   = "${var.name}: record every session to the locked log archive (SEC-015)"
    sessionType   = "Standard_Stream"
    inputs = {
      s3BucketName                = var.trail_bucket
      s3KeyPrefix                 = "ssm-sessions/${var.name}/"
      s3EncryptionEnabled         = true
      cloudWatchLogGroupName      = aws_cloudwatch_log_group.trail.name
      cloudWatchEncryptionEnabled = true
      idleSessionTimeout          = "20"
      maxSessionDuration          = "120"
      runAsEnabled                = false
    }
  })

  tags = var.tags
}
