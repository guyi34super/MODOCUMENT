output "vault_arn" {
  value       = aws_backup_vault.primary.arn
  description = "Primary (locked) backup vault ARN."
}

output "replica_vault_arn" {
  value       = aws_backup_vault.replica.arn
  description = "Cross-region (ap-southeast-2) replica vault ARN."
}

output "log_archive_bucket" {
  value       = aws_s3_bucket.log_archive.id
  description = "7-year WORM log-archive bucket (CloudTrail / app-log / audit-export destination)."
}

output "log_archive_bucket_arn" {
  value       = aws_s3_bucket.log_archive.arn
  description = "ARN of the log-archive bucket."
}
