package com.modocument.application.usecase;

import com.modocument.application.dto.AccessReviewRowDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SEC-013 recertification worksheet: bank-scoped, 90-day inactivity
 * flagged (including never-used accounts older than the window, but not
 * freshly provisioned joiners), and the generation itself audited.
 */
class AccessReviewUseCaseTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserMfaRepository userMfa = mock(UserMfaRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final AccessReviewUseCase useCase = new AccessReviewUseCase(
            FixedUser.admin(100), users, userMfa, audit);

    private static AppUser user(int id, LocalDateTime lastLogin, LocalDateTime created) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setBankId(Bank.DEFAULT_BANK_ID);
        user.setRole(Role.READ_ONLY);
        user.setEmail("user" + id + "@example.com");
        user.setPasswordHash("hash");
        user.setLastLoginAt(lastLogin);
        user.setCreatedDate(created);
        return user;
    }

    @Test
    void flagsNinetyDayInactivityAndCountsDaysSinceLastLogin() {
        LocalDateTime now = LocalDateTime.now();
        AppUser active = user(1, now.minusDays(10), now.minusDays(400));
        AppUser dormant = user(2, now.minusDays(120), now.minusDays(400));
        AppUser neverUsedOld = user(3, null, now.minusDays(120));
        AppUser freshJoiner = user(4, null, now.minusDays(1));
        when(users.listByBank(Bank.DEFAULT_BANK_ID))
                .thenReturn(List.of(active, dormant, neverUsedOld, freshJoiner));
        when(userMfa.enabledUserIds(anyCollection())).thenReturn(Set.of(1));

        List<AccessReviewRowDto> rows = useCase.generate();

        assertThat(rows).extracting(AccessReviewRowDto::inactive90d)
                .containsExactly(false, true, true, false);
        assertThat(rows.get(0).daysSinceLastLogin()).isEqualTo(10);
        assertThat(rows.get(1).daysSinceLastLogin()).isEqualTo(120);
        assertThat(rows.get(2).daysSinceLastLogin()).isNull();
        assertThat(rows.get(0).mfaEnabled()).isTrue();
        assertThat(rows.get(1).mfaEnabled()).isFalse();

        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("ACCESS_REVIEW_GENERATED");
        assertThat(String.valueOf(captor.getValue().newValues())).contains("users=4");
    }
}
