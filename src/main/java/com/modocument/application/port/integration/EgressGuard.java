package com.modocument.application.port.integration;

/**
 * Bank-aware outbound destination check for DIRECT-mode connections (ws25,
 * SE1). Public hosts pass under the global {@code OutboundHostPolicy}; a host
 * resolving to a private/VPN/PrivateLink address passes only when it matches
 * an APPROVED {@code BankEgressRule} of {@code bankId} (host or CIDR + port).
 * Connectors call it right before connecting (resolve, check, connect to the
 * checked address) and never follow redirects on authenticated calls.
 */
public interface EgressGuard {

    /** @throws SecurityException naming the host (never the credentials) when refused */
    void checkDestination(int bankId, String host, int port);
}
