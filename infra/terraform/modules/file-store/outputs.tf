output "bucket_name" {
  value       = aws_s3_bucket.files.id
  description = "File-store bucket (MODOCUMENT_STORAGE_S3_BUCKET)."
}

output "bucket_arn" {
  value       = aws_s3_bucket.files.arn
  description = "File-store bucket ARN (task-role policy scope)."
}

output "kms_key_arn" {
  value       = aws_kms_key.files.arn
  description = "CMK of the bucket (MODOCUMENT_STORAGE_S3_KMS_KEY_ID; task-role kms:GenerateDataKey/Decrypt scope)."
}
