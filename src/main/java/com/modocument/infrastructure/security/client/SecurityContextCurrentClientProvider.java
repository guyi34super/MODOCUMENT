package com.modocument.infrastructure.security.client;

import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The machine caller of the current request (ws26): the
 * {@link ClientAuthentication} in the security context, whichever method
 * authenticated it (client token, API key, client certificate). Empty for
 * user requests and anonymous ones.
 */
@Component
public class SecurityContextCurrentClientProvider implements CurrentClientProvider {

    @Override
    public Optional<ClientPrincipal> current() {
        return ClientAuthentication.current();
    }
}
