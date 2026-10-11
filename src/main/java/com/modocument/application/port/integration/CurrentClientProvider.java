package com.modocument.application.port.integration;

import java.util.Optional;

/** The machine caller of the current request, if any (ws26, SE2 implements). */
public interface CurrentClientProvider {

    Optional<ClientPrincipal> current();

    default ClientPrincipal require() {
        return current().orElseThrow(() -> new IllegalStateException("not a client request"));
    }
}
