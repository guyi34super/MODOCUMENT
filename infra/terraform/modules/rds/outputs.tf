output "db_instance_arn" {
  value       = aws_db_instance.this.arn
  description = "RDS instance ARN (the backups module's selection target)."
}

output "db_instance_id" {
  value       = aws_db_instance.this.identifier
  description = "RDS instance identifier (alarm dimension for security-monitoring)."
}

output "db_endpoint" {
  value       = aws_db_instance.this.endpoint
  description = "host:port endpoint for MODOCUMENT_DB_URL."
}

output "master_user_secret_arn" {
  value       = try(aws_db_instance.this.master_user_secret[0].secret_arn, null)
  description = "Secrets Manager secret holding the RDS-managed master password."
}

output "kms_key_arn" {
  value       = aws_kms_key.db.arn
  description = "CMK encrypting the database storage."
}
