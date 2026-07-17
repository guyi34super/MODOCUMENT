package com.modocument.infrastructure.extraction.worker;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stable identity of this application instance, used for node-affine tasks
 * (per-run credentials never leave the node that holds them).
 */
@Component
public class NodeIdentity {

    private final String id = "node-" + UUID.randomUUID();

    public String id() {
        return id;
    }
}
