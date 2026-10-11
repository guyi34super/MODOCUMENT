package com.modocument.domain.model.integration;

/**
 * Where a connection is executed from (ws25). Stored by NAME in
 * {@code IntegrationConnection.Mode}.
 */
public enum ConnectionMode {

    /**
     * From MoDocument's cloud (public API, VPN or PrivateLink). Credentials
     * sit encrypted in the {@code SecretVault}; private destinations need an
     * approved {@code BankEgressRule}.
     */
    DIRECT,

    /**
     * Through the bank's on-prem Connector Agent. The agent holds the
     * credentials; the connection stores only a {@code SecretRef} name and the
     * agent's id.
     */
    AGENT
}
