package com.modocument.application.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.Template;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shared template-update path (ws10 extraction): owner scoping and the
 * C7 old/new audit exemplar, shared by the direct endpoint (config-off) and
 * the TEMPLATE_UPDATE applier.
 */
class UpdateTemplateUseCaseTest {

    private static final int OWNER = 1;

    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final UpdateTemplateUseCase useCase = new UpdateTemplateUseCase(repo, audit);

    @Test
    void refusesTemplatesTheOwnerDoesNotOwnLikeMissingIds() {
        when(repo.getByIdAndOwner(1, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(1, "NewName", "NewDescription", OWNER))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void appliesTheChangeAndAuditsOldAndNewSnapshots() {
        Template existing = new Template(1, "OldName", "OldDescription");
        when(repo.getByIdAndOwner(1, OWNER)).thenReturn(Optional.of(existing));
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
}
