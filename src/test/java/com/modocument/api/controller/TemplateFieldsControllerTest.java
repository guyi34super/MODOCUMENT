package com.modocument.api.controller;

import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.domain.entity.TemplateFields;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateFieldsControllerTest {

    private final TemplateFieldsRepository repo = mock(TemplateFieldsRepository.class);
    // Ownership is verified elsewhere (TemplateOwnership); a permissive mock lets
    // these tests focus on the controller's own behaviour.
    private final TemplateOwnership ownership = mock(TemplateOwnership.class);
    private final TemplateFieldsController controller =
            new TemplateFieldsController(repo, ownership);

    private static TemplateFields templateFields(int id, int templateId, String fieldName) {
        TemplateFields fields = new TemplateFields();
        fields.setTemplateFieldsId(id);
        fields.setTemplateId(templateId);
        fields.setFieldName(fieldName);
        return fields;
    }

    @Test
    void getAllRequiresATemplateId() {
        // A global list would leak every user's fields, so templateId is required.
        ResponseEntity<?> result = controller.getAll(null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getAllScopesToATemplateWhenAsked() {
        when(repo.listByTemplateId(1)).thenReturn(List.of(templateFields(1, 1, "FirstName")));

        ResponseEntity<?> result = controller.getAll(1);

        assertThat((List<?>) result.getBody()).hasSize(1);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).list();
    }

    @Test
    void getReturnsNotFoundWhenIdDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.getTemplateFieldsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturnsOkWithTemplateFields() {
        when(repo.getById(1)).thenReturn(Optional.of(templateFields(1, 1, "FirstName")));

        ResponseEntity<?> result = controller.getTemplateFieldsById(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        TemplateFields returned = (TemplateFields) result.getBody();
        assertThat(returned.getTemplateFieldsId()).isEqualTo(1);
        assertThat(returned.getTemplateId()).isEqualTo(1);
        assertThat(returned.getFieldName()).isEqualTo("FirstName");
    }

    @Test
    void createReturnsCreatedWithNewTemplateFields() {
        ResponseEntity<?> result = controller.create(templateFields(0, 1, "NewField"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TemplateFields returned = (TemplateFields) result.getBody();
        assertThat(returned.getFieldName()).isEqualTo("NewField");
        assertThat(returned.getTemplateId()).isEqualTo(1);
    }

    /** The editor's data type is kept (it used to be dropped: FK violation on id 0). */
    @Test
    void createKeepsTheRequestedDataType() {
        TemplateFields request = templateFields(0, 1, "Amount");
        request.setTemplateDataTypeId(2);

        ResponseEntity<?> result = controller.create(request);

        assertThat(((TemplateFields) result.getBody()).getTemplateDataTypeId()).isEqualTo(2);
    }

    /** ws12 (PRIV-004): the explicit PII tag round-trips through create/update/get. */
    @Test
    void piiKindRoundTripsThroughCreateUpdateAndGet() {
        TemplateFields tagged = templateFields(0, 1, "CustomerNic");
        tagged.setPiiKind(com.modocument.domain.model.PiiKind.NIC);

        ResponseEntity<?> created = controller.create(tagged);
        assertThat(((TemplateFields) created.getBody()).getPiiKind())
                .isEqualTo(com.modocument.domain.model.PiiKind.NIC);

        TemplateFields existing = templateFields(1, 1, "CustomerNic");
        existing.setPiiKind(com.modocument.domain.model.PiiKind.NIC);
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(TemplateFields.class))).thenAnswer(inv -> inv.getArgument(0));

        TemplateFields retag = templateFields(1, 1, "CustomerNic");
        retag.setPiiKind(com.modocument.domain.model.PiiKind.ACCOUNT_NUMBER);
        ResponseEntity<?> updated = controller.update(1, retag);
        assertThat(existing.getPiiKind())
                .isEqualTo(com.modocument.domain.model.PiiKind.ACCOUNT_NUMBER);
        assertThat(((TemplateFields) updated.getBody()).getPiiKind())
                .isEqualTo(com.modocument.domain.model.PiiKind.ACCOUNT_NUMBER);

        ResponseEntity<?> fetched = controller.getTemplateFieldsById(1);
        assertThat(((TemplateFields) fetched.getBody()).getPiiKind())
                .isEqualTo(com.modocument.domain.model.PiiKind.ACCOUNT_NUMBER);
    }

    @Test
    void updateReturnsNotFoundWhenTemplateFieldsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.update(1, templateFields(1, 1, "TestField"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesChangesWhenUpdateIsSuccessful() {
        TemplateFields existing = templateFields(1, 1, "OldField");
        when(repo.getById(1)).thenReturn(Optional.of(existing));
        when(repo.update(any(TemplateFields.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> result = controller.update(1, templateFields(1, 2, "NewField"));

        assertThat(existing.getFieldName()).isEqualTo("NewField");
        assertThat(existing.getTemplateId()).isEqualTo(2);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteReturnsNoContent() {
        when(repo.getById(1)).thenReturn(Optional.of(templateFields(1, 1, "FirstName")));

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteReturnsNotFoundWhenTemplateFieldsDoesNotExist() {
        when(repo.getById(1)).thenReturn(Optional.empty());

        ResponseEntity<?> result = controller.delete(1);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
