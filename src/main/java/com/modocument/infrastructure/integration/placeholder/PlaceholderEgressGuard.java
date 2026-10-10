package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.EgressGuard;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import org.springframework.stereotype.Component;

/** Until SE1's bank-aware guard lands: the global policy only (no private destinations). */
@Component
class PlaceholderEgressGuard implements EgressGuard {

    private final OutboundHostPolicy policy;

    PlaceholderEgressGuard(OutboundHostPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void checkDestination(int bankId, String host, int port) {
        if (!policy.isHostAllowed(host)) {
            throw new SecurityException("The destination " + host + " is not an allowed egress target.");
        }
    }
}
