package com.modocument.application.privacy;

import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiiMaskingServiceTest {

    private static final int TEMPLATE = 5;

    private final TemplateFieldsRepository fields = mock(TemplateFieldsRepository.class);
    private final ValidationRuleRepository rules = mock(ValidationRuleRepository.class);

    private static TemplateFields field(String name, PiiKind kind) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(TEMPLATE);
        field.setFieldName(name);
        field.setPiiKind(kind);
        return field;
    }

    private PiiMaskingService service(Role role) {
        when(rules.getRulesByTemplate(TEMPLATE)).thenReturn(List.of());
        return new PiiMaskingService(
                new MaskingPolicy(new MaskingProperties()),
                new PiiClassifier(),
                fields, rules,
                new FixedUser(1, 1, role));
    }

    private static TableData table() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("CustomerNic", "M090976290148E");
        row.put("AccountNumber", "00445566778899");
        row.put("Email", "jane@acme.mu");
        row.put("City", "Curepipe");
        return new TableData(
                List.of("CustomerNic", "AccountNumber", "Email", "City"), List.of(row));
    }

    @Test
    void exportMasksExplicitlyTaggedAndAutoClassifiedColumns() {
        when(fields.listByTemplateId(TEMPLATE)).thenReturn(List.of(
                field("CustomerNic", PiiKind.NIC),
                field("AccountNumber", PiiKind.ACCOUNT_NUMBER),
                field("Email", null),      // auto-classified by name
                field("City", null)));     // auto-classified NONE

        TableData masked = service(Role.READ_WRITE).maskForExport(TEMPLATE, table());

        Map<String, String> row = masked.getRows().get(0);
        assertThat(row.get("CustomerNic")).isEqualTo("********");
        assertThat(row.get("AccountNumber")).isEqualTo("****8899");
        assertThat(row.get("Email")).isEqualTo("j***@acme.mu");
        assertThat(row.get("City")).isEqualTo("Curepipe");
        // The input table is never mutated — exports work on a masked copy.
        assertThat(table().getRows().get(0).get("CustomerNic")).isEqualTo("M090976290148E");
    }

    /** PRIV-004: the first classification is persisted, explicit tags never are. */
    @Test
    void firstClassificationIsPersistedOnTheField() {
        TemplateFields email = field("Email", null);
        TemplateFields city = field("City", null);
        TemplateFields nic = field("CustomerNic", PiiKind.NIC);
        when(fields.listByTemplateId(TEMPLATE)).thenReturn(List.of(email, city, nic));

        service(Role.READ_WRITE).maskForExport(TEMPLATE, table());

        ArgumentCaptor<TemplateFields> saved = ArgumentCaptor.forClass(TemplateFields.class);
        verify(fields, org.mockito.Mockito.times(2)).update(saved.capture());
        assertThat(email.getPiiKind()).isEqualTo(PiiKind.EMAIL);
        assertThat(city.getPiiKind()).isEqualTo(PiiKind.NONE);
        assertThat(saved.getAllValues()).containsExactlyInAnyOrder(email, city);
        // The explicit tag was not re-written.
        assertThat(saved.getAllValues()).doesNotContain(nic);
    }

    @Test
    void readOnlyScreenMasksParsedMappedAndValidationPayloads() {
        when(fields.listByTemplateId(TEMPLATE)).thenReturn(List.of(
                field("CustomerNic", PiiKind.NIC),
                field("AccountNumber", PiiKind.ACCOUNT_NUMBER),
                field("Email", PiiKind.EMAIL),
                field("City", PiiKind.NONE)));
        TableData parsed = new TableData(List.of("nic no", "city"),
                List.of(Map.of("nic no", "M090976290148E", "city", "Curepipe")));
        IngestionJobDto view = new IngestionJobDto(7, TEMPLATE, "f.csv", null,
                "VALIDATED", "COMPLETED", null, null,
                parsed, null, null, table(),
                List.of(new RowValidationResult(0, "AccountNumber", "00445566778899",
                        "value '00445566778899' is not valid", "REGEX")),
                0L, List.of(), List.of(), null, null, null);

        IngestionJobDto masked = service(Role.READ_ONLY).maskJobView(view);

        // Mapped rows: by the template-field classification.
        assertThat(masked.mappedRows().getRows().get(0).get("CustomerNic")).isEqualTo("********");
        assertThat(masked.mappedRows().getRows().get(0).get("City")).isEqualTo("Curepipe");
        // Parsed (pre-mapping source columns): by header + shape inference.
        assertThat(masked.parsedTable().getRows().get(0).get("nic no")).isEqualTo("********");
        assertThat(masked.parsedTable().getRows().get(0).get("city")).isEqualTo("Curepipe");
        // Validation payloads: raw value by kind, message by shape.
        assertThat(masked.validationResults().get(0).rawValue()).isEqualTo("****8899");
        assertThat(masked.validationResults().get(0).errorMessage())
                .doesNotContain("00445566778899").contains("****8899");
    }

    /** The owner's working screen stays raw: cell editing needs true values. */
    @Test
    void workingScreenIsRawForWritingRoles() {
        IngestionJobDto view = new IngestionJobDto(7, TEMPLATE, "f.csv", null,
                "VALIDATED", "COMPLETED", null, null,
                null, null, null, table(), null, 0L, List.of(), List.of(), null, null, null);

        IngestionJobDto same = service(Role.READ_WRITE).maskJobView(view);

        assertThat(same).isSameAs(view);
        verify(fields, never()).listByTemplateId(TEMPLATE);
    }

    @Test
    void reportTextMasksShapesAndNullsSurvive() {
        PiiMaskingService service = service(Role.READ_WRITE);

        assertThat(service.maskReportText("NIC M090976290148E, acct 00445566778899"))
                .isEqualTo("NIC ********, acct ****8899");
        assertThat(service.maskReportText(null)).isNull();
    }
}
