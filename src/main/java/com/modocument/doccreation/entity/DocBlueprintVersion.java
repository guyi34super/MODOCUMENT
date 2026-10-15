package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One version of a blueprint's section structure (V291). The section rows of
 * a version carry the same {@code Version} number; documents pin the version
 * they were started from.
 */
@Entity
@Table(name = "DocBlueprintVersion")
public class DocBlueprintVersion {

    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocBlueprintVersionId")
    private int docBlueprintVersionId;

    @Column(name = "BlueprintId", nullable = false)
    private int blueprintId;

    /** The owning blueprint's bank; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "Version", nullable = false)
    private int version;

    /** AI, HEURISTIC or MANUAL; null for versions created before versioning existed. */
    @Column(name = "SectionSource", length = 16)
    private String sectionSource;

    @Column(name = "Note", length = 500)
    private String note;

    @Column(name = "CreatedByUserId")
    private Integer createdByUserId;

    @Column(name = "CreatedDate", nullable = false)
    private LocalDateTime createdDate;

    public DocBlueprintVersion() {
    }

    public int getDocBlueprintVersionId() {
        return docBlueprintVersionId;
    }

    public void setDocBlueprintVersionId(int docBlueprintVersionId) {
        this.docBlueprintVersionId = docBlueprintVersionId;
    }

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSectionSource() {
        return sectionSource;
    }

    public void setSectionSource(String sectionSource) {
        this.sectionSource = sectionSource;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Integer createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }
}
