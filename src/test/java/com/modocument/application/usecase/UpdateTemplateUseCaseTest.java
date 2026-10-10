package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shared template-update path (ws10 extraction): the write rule (ws22:
 * owner for PRIVATE, template:publish for BANK — judged for the acting user,
 * the maker on apply) and the C7 old/new audit exemplar, shared by the
 * direct endpoint (config-off) and the TEMPLATE_UPDATE applier.
 */
class UpdateTemplateUseCaseTest {

    private static final int OWNER = 1;
    private static final int CHECKER = 2;
    private static final int BANK = Bank.DEFAULT_BANK_ID;

    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final UpdateTemplateUseCase useCase = useCaseAs(OWNER, Role.READ_WRITE);

    private UpdateTemplateUseCase useCaseAs(int callerId, Role role) {
        return new UpdateTemplateUseCase(
                new TemplateOwnership(repo, new FixedUser(callerId, BANK, role), users),
                repo, audit);
    }

    private static Template owned(TemplateVisibility visibility) {
        Template template = new Template(1, "OldName", "OldDescription");
        template.setOwnerUserId(OWNER);
        template.setVisibility(visibility);
        return template;
    }

    private void maker(Role role) {
        AppUser maker = new AppUser();
        maker.setAppUserId(OWNER);
        maker.setBankId(BANK);
        maker.setRole(role);
        when(users.getById(OWNER)).thenReturn(Optional.of(maker));
    }

    @Test
    void refusesTemplatesTheOwnerDoesNotOwnLikeMissingIds() {
        when(repo.getAccessible(1, OWNER, BANK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(1, "NewName", "NewDescription", OWNER))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void appliesTheChangeAndAuditsOldAndNewSnapshots() {
        Template existing = owned(TemplateVisibility.PRIVATE);
        when(repo.getAccessible(1, OWNER, BANK)).thenReturn(Optional.of(existing));
        when(repo.update(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

        Template result = useCase.handle(1, "NewName", "NewDescription", OWNER);

        assertThat(existing.getName()).isEqualTo("NewName");
        assertThat(existing.getDescription()).isEqualTo("NewDescription");
        assertThat(result.getName()).isEqualTo("NewName");
        // The C7 exemplar: old and new field-level snapshots are audited.
        verify(audit).success(
                ArgumentMatchers.eq("TEMPLATE_UPDATED"),
                ArgumentMatchers.eq("Template"),
                ArgumentMatchers.eq("1"),
                ArgumentMatchers.argThat(old -> old.toString().contains("OldName")),
                ArgumentMatchers.argThat(now -> now.toString().contains("NewName")));
    }

    @Test
    void aSharedTemplateIsReadOnlyWithoutTemplatePublishEvenForItsOwner() {
        when(repo.getAccessible(1, OWNER, BANK))
                .thenReturn(Optional.of(owned(TemplateVisibility.BANK)));

        assertThatThrownBy(() -> useCase.handle(1, "NewName", null, OWNER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onApplyTheMakersRightsCountNotTheCheckers() {
        // The checker (an ADMIN) applies a change submitted by a maker whose
        // role no longer grants template:publish: refused.
        when(repo.getAccessible(1, OWNER, BANK))
                .thenReturn(Optional.of(owned(TemplateVisibility.BANK)));
        maker(Role.READ_WRITE);
        UpdateTemplateUseCase asChecker = useCaseAs(CHECKER, Role.ADMIN);

        assertThatThrownBy(() -> asChecker.handle(1, "NewName", null, OWNER))
                .isInstanceOf(AccessDeniedException.class);

        // ... and with a publishing maker the change applies.
        maker(Role.ADMIN);
        when(repo.update(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(asChecker.handle(1, "NewName", null, OWNER).getName()).isEqualTo("NewName");
    }
}
