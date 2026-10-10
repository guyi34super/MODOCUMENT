package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import org.springframework.stereotype.Component;

/** Until SE1's vault lands: refuses, so no credential is ever stored unencrypted. */
@Component
class PlaceholderSecretVault implements SecretVault {

    @Override
    public Sealed seal(int bankId, SecretMaterial material) {
        throw new IllegalStateException("The connection secret vault is not available yet.");
    }

    @Override
    public SecretMaterial open(int bankId, Sealed sealed) {
        throw new IllegalStateException("The connection secret vault is not available yet.");
    }
}
