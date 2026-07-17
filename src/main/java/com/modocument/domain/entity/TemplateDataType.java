package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "TemplateDataTypes")
public class TemplateDataType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateDataTypeId")
    private int templateDataTypeId;

    @Column(name = "DataType")
    private String dataType;

    public TemplateDataType() {
    }

    public TemplateDataType(int templateDataTypeId, String dataType) {
        this.templateDataTypeId = templateDataTypeId;
        this.dataType = dataType;
    }

    public int getTemplateDataTypeId() {
        return templateDataTypeId;
    }

    public void setTemplateDataTypeId(int templateDataTypeId) {
        this.templateDataTypeId = templateDataTypeId;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }
}
