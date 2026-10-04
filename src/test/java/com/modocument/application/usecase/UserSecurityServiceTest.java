package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The B5 seam: ONE method bumps AppUsers.TokenVersion (killing every
 * outstanding access token via the RevocationValidator) and audits itself
 * with the reason — password change uses it today, the ws09 role-change
 * path tomorrow.
 */
class UserSecurityServiceTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final UserSecurityService service = new UserSecurityService(repo, audit);

    @Test
    void bumpIncrementsTheVersionPersistsAndAuditsTheReason() {
        AppUser user = new AppUser(7, "bump@modocument.local", "hash", "Bump");
        user.setBankId(1);
        user.setRole(Role.READ_ONLY);
        user.setTokenVersion(3);
        when(repo.getById(7)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AppUser bumped = service.bumpTokenVersion(7, "role-change");

        assertThat(bumped.getTokenVersion()).isEqualTo(4);
        verify(repo).save(user);

        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(captor.capture());
        AuditEventSpec recorded = captor.getValue();
        assertThat(recorded.action()).isEqualTo("TOKEN_VERSION_BUMPED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(recorded.actorUserId()).isEqualTo(7);
        assertThat(recorded.actorEmail()).isEqualTo("bump@modocument.local");
        assertThat(recorded.entityType()).isEqualTo("AppUser");
        assertThat(recorded.entityId()).isEqualTo("7");
        assertThat(String.valueOf(recorded.newValues())).contains("role-change");
    }

    @Test
    void bumpForAMissingUserThrows() {
        when(repo.getById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bumpTokenVersion(99, "role-change"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
