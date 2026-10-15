package com.modocument.doccreation.dto;

/**
 * Result of {@code POST /api/admin/doccreation/purge-legacy}: how many
 * Word-based templates and documents of the caller's bank were deleted, and
 * how many stored files were scheduled for deletion.
 */
public record LegacyPurgeResultDto(int blueprints, int instances, int files) {
}
