package com.modocument.connector.core.spi;

/** Outcome of one outbound batch; {@code remoteReference} e.g. the uploaded file name or the HTTP status. */
public record WriteResult(int recordsWritten, String remoteReference) {
}
