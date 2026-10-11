package com.modocument.agent.allowlist;

/** A task asked for something the bank's local allow-list does not name. */
public class AllowListViolation extends RuntimeException {

    public AllowListViolation(String message) {
        super(message);
    }
}
