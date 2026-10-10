package com.modocument.application.usecase;

import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApiClientIssuer} (ws26, contracts §7.1): the ApiClient of an
 * enrolling Connector Agent — Kind AGENT, scope AGENT_CONNECT only, no
 * expiry, no IP restriction, owned by no user (the jobs an agent's runs
 * create belong to the flow's creator), authenticated with OAuth2 client
 * credentials (the secret is returned exactly once) or MTLS (the agent's
 * certificate). SE5's {@code POST /api/agent/v1/enroll} calls it after the
 * one-time enrolment token checked out; that enrolment approval is the
 * dual control, so this is not another maker/checker change.
 *
 * <p>Tenant: the rows are written in {@code bankId}. When the caller has not
 * bound a tenant (the public enrolment endpoint), this binds it with
 * {@code TenantContext.runAs(bankId)} (registered call site); a caller bound
 * to a DIFFERENT bank is refused.
 */
@Component
public class AgentApiClientIssuer implements ApiClientIssuer {

    private final ApiClientProvisioning provisioning;
    private final TransactionTemplate tx;

    public AgentApiClientIssuer(ApiClientProvisioning provisioning,
                                PlatformTransactionManager transactionManager) {
        this.provisioning = provisioning;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public Issued issueAgentClient(int bankId, int agentId, String name, ApiAuthMethod method,
                                   String certificatePem) {
        if (method == null || method == ApiAuthMethod.API_KEY) {
            throw new IllegalArgumentException(
                    "A Connector Agent authenticates with OAUTH2_CLIENT_CREDENTIALS or MTLS.");
        }
        Optional<Integer> bound = TenantContext.currentBankId();
        if (bound.isPresent() && bound.get() != bankId) {
            throw new IllegalStateException("Cannot issue an agent client for another bank.");
        }
        if (bound.isPresent() || TenantContext.isSystem()) {
            return issue(bankId, agentId, name, method, certificatePem);
        }
        try {
            return TenantContext.runAs(bankId,
                    () -> issue(bankId, agentId, name, method, certificatePem));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue the agent client.", e);
        }
    }

    private Issued issue(int bankId, int agentId, String name, ApiAuthMethod method,
                         String certificatePem) {
        return tx.execute(status -> {
            provisioning.requireMethodAllowed(method, bankId);
            String fingerprint = null;
            String subject = null;
            if (method == ApiAuthMethod.MTLS) {
                X509Certificate certificate = ApiClientProvisioning.parseCertificate(certificatePem);
                fingerprint = ApiClientProvisioning.fingerprint(certificate);
                provisioning.requireCertificateFree(fingerprint);
                subject = certificate.getSubjectX500Principal().getName();
                if (subject.length() > com.modocument.domain.entity.ApiClient.CERT_SUBJECT_MAX) {
                    subject = subject.substring(0,
                            com.modocument.domain.entity.ApiClient.CERT_SUBJECT_MAX);
                }
            }
            String clientName = name == null || name.isBlank()
                    ? "agent-" + agentId : ApiClientProvisioning.requireName(name);
            ApiClientProvisioning.Created created = provisioning.createClient(bankId,
                    ApiClientKind.AGENT, clientName, method, ApiClientProvisioning.AGENT_SCOPES,
                    List.of(), null, fingerprint, subject, null, agentId, null);
            return new Issued(created.client().getApiClientId(), created.client().getClientId(),
                    created.deliverableSecret(), method);
        });
    }
}
