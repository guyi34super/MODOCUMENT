package com.modocument.domain.model.integration;

/**
 * What kind of bank system an {@code IntegrationConnection} points at (ws25).
 * Stored by NAME in {@code IntegrationConnection.Type}.
 */
public enum ConnectionType {

    /** A REST/JSON API: GET pull with pagination, POST/PUT push in batches. */
    REST_API,

    /** A relational database: read an allow-listed table/view, write a staging table. */
    DATABASE,

    /** An SFTP file drop: pull files into ingestion, push result files back. */
    SFTP,

    /** An HTTPS endpoint of the bank that receives signed event/result posts. */
    WEBHOOK
}
