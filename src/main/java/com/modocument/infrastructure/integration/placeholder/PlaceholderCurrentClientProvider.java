package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Until SE2's client authentication lands: no request is a client request. */
@Component
class PlaceholderCurrentClientProvider implements CurrentClientProvider {

    @Override
    public Optional<ClientPrincipal> current() {
        return Optional.empty();
    }
}
