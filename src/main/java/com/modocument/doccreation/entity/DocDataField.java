package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


/**
 * A field of a data template (V330): unique {@link #fieldName} per template,
 * a region kind, the FieldProps JSON, and the flow field it was copied from.
 */
@Entity
@Table(name = "DocDataField")
public class DocDataField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocDataFieldId")
    private Long docDataFieldId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "DataTemplateId", nullable = false)
    private int dataTemplateId;

    @Column(name = "FieldName", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "Label", nullable = false, length = 200)
    private String label;

    @Column(name = "Kind", nullable = false, length = 16)
    private String kind = "TEXT";

    @Column(name = "PiiKind", length = 32)
    private String piiKind;

    @Column(name = "PropsJson", columnDefinition = "text")
    private String propsJson;

    @Column(name = "SourceFieldName", length = 255)
    private String sourceFieldName;

    /** V333: the flow template this field came from (a template may mix several), null = made by hand. */
    @Column(name = "SourceTemplateId")
    private Integer sourceTemplateId;

    @Column(name = "SortOrder", nullable = false)
    private int sortOrder;

    public DocDataField() {
    }

    public Long getDocDataFieldId() {
        return docDataFieldId;
    }

    public void setDocDataFieldId(Long docDataFieldId) {
        this.docDataFieldId = docDataFieldId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getDataTemplateId() {
        return dataTemplateId;
    }

    public void setDataTemplateId(int dataTemplateId) {
        this.dataTemplateId = dataTemplateId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPiiKind() {
        return piiKind;
    }

    public void setPiiKind(String piiKind) {
        this.piiKind = piiKind;
    }

    public String getPropsJson() {
        return propsJson;
    }

    public void setPropsJson(String propsJson) {
        this.propsJson = propsJson;
    }

    public String getSourceFieldName() {
        return sourceFieldName;
    }

    public void setSourceFieldName(String sourceFieldName) {
        this.sourceFieldName = sourceFieldName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }


    public Integer getSourceTemplateId() {
        return sourceTemplateId;
    }

    public void setSourceTemplateId(Integer sourceTemplateId) {
        this.sourceTemplateId = sourceTemplateId;
    }
}
