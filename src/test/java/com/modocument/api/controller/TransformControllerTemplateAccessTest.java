package com.modocument.api.controller;

import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.TransformService;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.usecase.UploadOwnership;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/transform/transform is a USE of the template the AI picked
 * (ws22 classification #20): a template shared with the caller's bank is
 * usable, another user's private one is a 404 before any extraction spend.
 * The HTTP matrix (TemplateVisibilityMatrixTest) cannot drive this route
 * without an LLM, so the gate is proven here with the real
 * TemplateOwnership.
 */
class TransformControllerTemplateAccessTest {

    private static final int USER = 21;
    private static final int TEMPLATE = 5;

    private final TransformService transform = mock(TransformService.class);
    private final TemplateFieldsRepository fields = mock(TemplateFieldsRepository.class);
    private final ImportHistoryRepository imports = mock(ImportHistoryRepository.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TransformController controller = new TransformController(transform, fields,
            imports, mock(ImportDetailsRepository.class),
            new TemplateOwnership(templates,
                    new FixedUser(USER, Bank.DEFAULT_BANK_ID, Role.READ_WRITE),
                    mock(AppUserRepository.class)),
            mock(UploadOwnership.class));

    @Test
    void aTemplateSharedWithTheBankIsUsable() throws Exception {
        Template shared = new Template(TEMPLATE, "Shared", null);
        shared.setOwnerUserId(99);
        shared.setVisibility(TemplateVisibility.BANK);
        when(transform.getTemplateId(any())).thenReturn(TEMPLATE);
        when(templates.getAccessible(TEMPLATE, USER, Bank.DEFAULT_BANK_ID))
                .thenReturn(Optional.of(shared));
        TemplateFields amount = new TemplateFields();
        amount.setFieldName("Amount");
        when(fields.listByTemplateId(TEMPLATE)).thenReturn(List.of(amount));
        when(transform.transform(any(), any())).thenReturn(new TransformResult("[]"));
        when(imports.add(any(ImportHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.transformFile(new TransformRequest("doc.pdf"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The import is the CALLER's, against the shared template.
        verify(imports).add(org.mockito.ArgumentMatchers.argThat(history ->
                history.getOwnerUserId() == USER && history.getTemplateId() == TEMPLATE));
    }

    @Test
    void anotherUsersPrivateTemplateIsNotFoundBeforeAnyExtraction() throws Exception {
        when(transform.getTemplateId(any())).thenReturn(TEMPLATE);
        when(templates.getAccessible(TEMPLATE, USER, Bank.DEFAULT_BANK_ID))
                .thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.transformFile(new TransformRequest("doc.pdf"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(transform, never()).transform(any(), any());
    }
}
