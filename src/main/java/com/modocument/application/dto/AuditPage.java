package com.modocument.application.dto;

import java.util.List;

/**
 * One keyset page of the audit viewer, newest first; {@code nextCursor} is
 * opaque (pass it back as {@code cursor}) and null on the last page.
 */
public record AuditPage(List<AuditEventView> events, String nextCursor) {
}
