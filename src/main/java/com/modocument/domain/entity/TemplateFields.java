package com.modocument.domain.entity;

import com.modocument.domain.model.PiiKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

@Entity
// SCHEMA PARITY with V30 (uq_templatefields_templateid_fieldname): no two
// fields of one template share a name. The app blocks duplicates up front
// (CreateTemplateWithFieldsUseCase); this is the DB truth for the race.
@Table(name = "TemplateFields", uniqueConstraints = @UniqueConstraint(
        name = "uq_templatefields_templateid_fieldname",
        columnNames = {"TemplateId", "FieldName"}))
// SCHEMA PARITY with V100 (ck_templatefields_piikind) on the H2 test schema:
// the PiiKind vocabulary is closed — see com.modocument.domain.model.PiiKind.
// NULL stays allowed as the "unclassified yet" state.
@Check(name = "ck_templatefields_piikind",
        constraints = "PiiKind in ('NIC', 'ACCOUNT_NUMBER', 'EMAIL', 'PHONE', 'NAME', 'NONE')")
public class TemplateFields {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateFieldsId")
    private int templateFieldsId;

    @Column(name = "TemplateId")
    private int templateId;

    /** The owning template's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    /** Doubles as the request schema cap when the entity arrives as a body. */
    @Size(max = 255)
    @Column(name = "FieldName")
    private String fieldName;

    @Column(name = "TemplateDataTypeId")
    private int templateDataTypeId;

    /**
     * PII classification (V100, PRIV-004): drives the role-by-channel
     * masking (gate C2). NULL = not classified yet — the first read that
     * needs it persists {@code PiiClassifier}'s inference here; an explicit
     * value (including {@link PiiKind#NONE}) is never overwritten.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "PiiKind", length = 20)
    private PiiKind piiKind;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "TemplateId", insertable = false, updatable = false)
    private Template template;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "TemplateDataTypeId", insertable = false, updatable = false)
    private TemplateDataType templateDataType;

    public TemplateFields() {
    }

    public int getTemplateFieldsId() {
        return templateFieldsId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public void setTemplateFieldsId(int templateFieldsId) {
        this.templateFieldsId = templateFieldsId;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public PiiKind getPiiKind() {
        return piiKind;
    }

    public void setPiiKind(PiiKind piiKind) {
        this.piiKind = piiKind;
    }

    public int getTemplateDataTypeId() {
        return templateDataTypeId;
    }

    public void setTemplateDataTypeId(int templateDataTypeId) {
        this.templateDataTypeId = templateDataTypeId;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public TemplateDataType getTemplateDataType() {
        return templateDataType;
    }

    public void setTemplateDataType(TemplateDataType templateDataType) {
        this.templateDataType = templateDataType;
    }
}
