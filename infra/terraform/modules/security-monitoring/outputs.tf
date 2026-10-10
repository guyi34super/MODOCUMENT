output "alert_topic_arn" {
  value       = aws_sns_topic.alerts.arn
  description = "SNS topic every alarm publishes to — the ws18 alert-channel seam."
}

output "guardduty_detector_id" {
  value       = aws_guardduty_detector.this.id
  description = "GuardDuty detector id (PRIV-003 evidence)."
}

output "trail_arn" {
  value       = aws_cloudtrail.this.arn
  description = "CloudTrail ARN (A6/SEC-015 evidence)."
}
