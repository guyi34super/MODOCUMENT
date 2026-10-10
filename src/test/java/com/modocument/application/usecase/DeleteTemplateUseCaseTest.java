package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeleteTemplateUseCaseTest {

    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository fields = mock(TemplateFieldsRepository.class);
    private final ValidationRuleRepository rules = mock(ValidationRuleRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final ImportHistoryRepository imports = mock(ImportHistoryRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);

    private static final int USER = 5;
    private static final int BANK = Bank.DEFAULT_BANK_ID;

    private DeleteTemplateUseCase useCaseFor(Role role) {
        TemplateOwnership ownership = new TemplateOwnership(templates,
                new FixedUser(USER, BANK, role), mock(AppUserRepository.class));
        return new DeleteTemplateUseCase(ownership, templates, fields, rules, jobs, imports,
                audit);
    }

    private final DeleteTemplateUseCase useCase = useCaseFor(Role.READ_WRITE);

    private Template templateExists() {
        Template template = new Template(3, "People", null);
        template.setOwnerUserId(USER);
        when(templates.getAccessible(3, USER, BANK)).thenReturn(Optional.of(template));
        return template;
    }

    /** ws22: a template another user owns and shared with the bank. */
    private Template sharedByAColleague() {
        Template template = new Template(3, "People", null);
        template.setOwnerUserId(77);
        template.setVisibility(TemplateVisibility.BANK);
        when(templates.getAccessible(3, USER, BANK)).thenReturn(Optional.of(template));
        return template;
    }

    @Test
    void aSharedTemplateNeedsTemplatePublish() {
        sharedByAColleague();

        assertThatThrownBy(() -> useCase.handle(3, USER))
                .isInstanceOf(AccessDeniedException.class);
        verify(templates, never()).delete(3);
    }

    @Test
    void sharedInUse409() {
        sharedByAColleague();
        when(jobs.countActiveByTemplateIdOfOtherUsers(3, 77)).thenReturn(2);

        assertThatThrownBy(() -> useCaseFor(Role.ADMIN).handle(3, USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 unfinished upload jobs of other users");
        verify(templates, never()).delete(3);
    }

    @Test
    void anAdminDeletesAnIdleSharedTemplate() {
        sharedByAColleague();

        useCaseFor(Role.ADMIN).handle(3, USER);

        verify(templates).delete(3);
    }

    @Test
    void deletesRulesAndFieldsBeforeTheTemplate() {
        templateExists();
        TemplateFields field = new TemplateFields();
        field.setTemplateFieldsId(11);
        field.setTemplateId(3);
        field.setFieldName("Email");
        when(fields.listByTemplateId(3)).thenReturn(List.of(field));
        when(rules.getRulesByTemplate(3)).thenReturn(List.of(
                new ValidationRule(3, "Email", RuleType.REQUIRED, "{}")));

        useCase.handle(3, USER);

        verify(rules).delete(3, "Email", RuleType.REQUIRED);
        verify(fields).delete(11);
        verify(templates).delete(3);
        // The C7 exemplar: the deletion records old values (name + fields).
        verify(audit).success(
                org.mockito.ArgumentMatchers.eq("TEMPLATE_DELETED"),
                org.mockito.ArgumentMatchers.eq("Template"),
                org.mockito.ArgumentMatchers.eq("3"),
                org.mockito.ArgumentMatchers.argThat(old ->
                        old.toString().contains("People") && old.toString().contains("Email")),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void refusesWhenUploadJobsStillReferenceTheTemplate() {
        templateExists();
        when(jobs.countByTemplateId(3)).thenReturn(2);

        assertThatThrownBy(() -> useCase.handle(3, USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 upload jobs");
        verify(templates, never()).delete(3);
        verify(fields, never()).delete(org.mockito.ArgumentMatchers.anyInt());
        // Nothing was deleted, so nothing may claim it was (C7).
        verify(audit, never()).success(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesWhenImportsStillReferenceTheTemplate() {
        templateExists();
        when(imports.countByTemplateId(3)).thenReturn(1);

        assertThatThrownBy(() -> useCase.handle(3, USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 import");
        verify(templates, never()).delete(3);
    }

    @Test
    void reportsAMissingTemplate() {
        when(templates.getAccessible(3, USER, BANK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(3, USER))
                .isInstanceOf(NoSuchElementException.class);
    }
}
