package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.domain.model.integration.ApiAuthMethod;
import org.springframework.stereotype.Component;

/** Until SE2's API clients land. */
@Component
class PlaceholderApiClientIssuer implements ApiClientIssuer {

    @Override
    public Issued issueAgentClient(int bankId, int agentId, String name, ApiAuthMethod method,
                                   String certificatePem) {
        throw new IllegalStateException("API clients are not available yet.");
    }
}
