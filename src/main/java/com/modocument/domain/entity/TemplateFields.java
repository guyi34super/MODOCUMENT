package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "TemplateFields")
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

    @Column(name = "FieldName")
    private String fieldName;

    @Column(name = "TemplateDataTypeId")
    private int templateDataTypeId;

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
