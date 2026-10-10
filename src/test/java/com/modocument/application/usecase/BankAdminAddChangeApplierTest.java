package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.BankDirectory.BankRef;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BANK_ADMIN_ADD checks the temporary password against the TARGET bank's
 * password policy (its {@code security.password.minLength}), not the
 * platform bank's — the maker is bound to the platform bank, the new
 * account is not.
 */
class BankAdminAddChangeApplierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STRICT_BANK = 77;
    private static final int LENIENT_BANK = 78;
    /** 20 characters: enough for the platform (12), too short for the strict bank (24). */
    private static final String PASSWORD = "Harbour-Lantern-58qz";

    private final PlatformBankLifecycleUseCase lifecycle = mock(PlatformBankLifecycleUseCase.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SettingsReader settings = mock(SettingsReader.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final BankAdminAddChangeApplier applier = new BankAdminAddChangeApplier(
            lifecycle, users, new PasswordPolicy(12, settings), encoder, MAPPER);

    @BeforeEach
    void banks() {
        when(settings.getIntForCurrentBank(SettingKeys.PASSWORD_MIN_LENGTH)).thenReturn(12);
        when(settings.getIntForPlatform(SettingKeys.PASSWORD_MIN_LENGTH)).thenReturn(12);
        when(settings.getInt(SettingKeys.PASSWORD_MIN_LENGTH, STRICT_BANK)).thenReturn(24);
        when(settings.getInt(SettingKeys.PASSWORD_MIN_LENGTH, LENIENT_BANK)).thenReturn(12);
        for (int bankId : new int[] {STRICT_BANK, LENIENT_BANK}) {
            when(lifecycle.requireCustomerBank(eq(bankId), anyString())).thenReturn(new BankRef(
                    bankId, "B" + bankId, "Bank " + bankId, BankKind.CUSTOMER, BankStatus.ACTIVE));
        }
        when(encoder.encode(PASSWORD)).thenReturn("{argon2}hash");
    }

    @Test
    void theTargetBanksMinimumLengthDecides() {
        assertThatThrownBy(() -> applier.prepare(request(STRICT_BANK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 24 characters")
                .hasMessageNotContaining(PASSWORD);

        PendingChangeApplier.Prepared prepared = applier.prepare(request(LENIENT_BANK));
        assertThat(prepared.payload().get("passwordHash").asText()).isEqualTo("{argon2}hash");
        assertThat(prepared.payload().toString()).doesNotContain(PASSWORD);
    }

    private static JsonNode request(int bankId) {
        return MAPPER.createObjectNode()
                .put("bankId", bankId)
                .put("email", "break-glass@bank.example")
                .put("displayName", "Break Glass")
                .put("temporaryPassword", PASSWORD)
                .put("reason", "Both admins left");
    }
}
