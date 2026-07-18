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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ImportDetails")
public class ImportDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ImportDetailsId")
    private int importDetailsId;

    @Column(name = "ImportHistoryId")
    private int importHistoryId;

    @Column(name = "ProcessStepId")
    private int processStepId;

    // Unbounded string so full JSON payloads fit: text on PostgreSQL, CLOB on
    // H2 (a plain @Lob would map to PostgreSQL's oid large-object type).
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "RawJson")
    private String rawJson;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ImportHistoryId", insertable = false, updatable = false)
    private ImportHistory importHistory;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ProcessStepId", insertable = false, updatable = false)
    private ProcessStep processStep;

    public ImportDetails() {
    }

    public int getImportDetailsId() {
        return importDetailsId;
    }

    public void setImportDetailsId(int importDetailsId) {
        this.importDetailsId = importDetailsId;
    }

    public int getImportHistoryId() {
        return importHistoryId;
    }

    public void setImportHistoryId(int importHistoryId) {
        this.importHistoryId = importHistoryId;
    }

    public int getProcessStepId() {
        return processStepId;
    }

    public void setProcessStepId(int processStepId) {
        this.processStepId = processStepId;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public ImportHistory getImportHistory() {
        return importHistory;
    }

    public void setImportHistory(ImportHistory importHistory) {
        this.importHistory = importHistory;
    }

    public ProcessStep getProcessStep() {
        return processStep;
    }

    public void setProcessStep(ProcessStep processStep) {
        this.processStep = processStep;
    }
}
