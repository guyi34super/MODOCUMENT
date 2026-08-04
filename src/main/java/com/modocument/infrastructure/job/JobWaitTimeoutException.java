package com.modocument.infrastructure.job;

/** Raised when a long-poll wait for job completion exceeds the configured timeout. */
public class JobWaitTimeoutException extends RuntimeException {

    public JobWaitTimeoutException(String message) {
        super(message);
    }
}
