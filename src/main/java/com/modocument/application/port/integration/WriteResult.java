package com.modocument.application.port.integration;

/** Outcome of one outbound batch (ws25); {@code remoteReference} e.g. the uploaded path or HTTP status. */
public record WriteResult(int recordsWritten, String remoteReference) {
}
