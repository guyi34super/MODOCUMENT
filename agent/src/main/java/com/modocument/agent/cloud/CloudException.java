package com.modocument.agent.cloud;

/**
 * A failed call to MoDocument. {@code status} is the HTTP status (0 for a
 * network error); the message never carries a credential or a response body
 * beyond the server's error code.
 */
public class CloudException extends RuntimeException {

    private final int status;

    public CloudException(int status, String message) {
        super(message);
        this.status = status;
    }

    public CloudException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }
}
