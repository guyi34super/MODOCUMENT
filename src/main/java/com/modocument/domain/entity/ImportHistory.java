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

import java.time.LocalDateTime;

@Entity
@Table(name = "ImportHistory")
public class ImportHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ImportHistoryId")
    private int importHistoryId;

    @Column(name = "ImportDate")
    private LocalDateTime importDate;

    @Column(name = "FileName")
    private String fileName;

    @Column(name = "CompanyId")
    private int companyId;

    @Column(name = "TemplateId")
    private int templateId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "TemplateId", insertable = false, updatable = false)
    private Template template;

    public ImportHistory() {
    }

    public int getImportHistoryId() {
        return importHistoryId;
    }

    public void setImportHistoryId(int importHistoryId) {
        this.importHistoryId = importHistoryId;
    }

    public LocalDateTime getImportDate() {
        return importDate;
    }

    public void setImportDate(LocalDateTime importDate) {
        this.importDate = importDate;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getCompanyId() {
        return companyId;
    }

    public void setCompanyId(int companyId) {
        this.companyId = companyId;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }
}
