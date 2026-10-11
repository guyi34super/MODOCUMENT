package com.modocument.connector.core.spi;

/** What a caller is about to do; decides which resource {@link Connector#authorize} checks. */
public enum Operation {
    /** Connect, authenticate, touch the configured resource. */
    TEST,
    /** Inbound: the source spec's table / path / folder. */
    READ,
    /** Outbound: the target spec's table / path / folder. */
    WRITE
}
