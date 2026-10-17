package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * A saved data source of a PDF template (V311,
 * docs/doccreation/flow-records-contract.md §1/§4): a flow template whose
 * records fill the template's regions through {@link #mappingJson} (region
 * key to template field name). One PRIMARY per blueprint; a LINKED source is
 * found from the primary record through its link fields.
 */
@Entity
@Table(name = "DocBlueprintSource", uniqueConstraints = @UniqueConstraint(
        name = "ux_docblueprintsource_blueprint_template", columnNames = {"BlueprintId", "TemplateId"}))
public class DocBlueprintSource {

    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_LINKED = "LINKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocBlueprintSourceId")
    private Long docBlueprintSourceId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "BlueprintId", nullable = false)
    private int blueprintId;

    @Column(name = "TemplateId", nullable = false)
    private int templateId;

    @Column(name = "Role", nullable = false, length = 16)
    private String role;

    @Column(name = "LinkFieldPrimary", length = 200)
    private String linkFieldPrimary;

    @Column(name = "LinkFieldThis", length = 200)
    private String linkFieldThis;

    @Column(name = "MappingJson", nullable = false, columnDefinition = "text")
    private String mappingJson;

    @Column(name = "SortOrder", nullable = false)
    private int sortOrder;

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;

    /** V332 (ws34): kept in step from the data-field mapping; a hand-made source is never Managed. */
    @Column(name = "Managed", nullable = false)
    private boolean managed;

    public DocBlueprintSource() {
    }

    public boolean isPrimary() {
        return ROLE_PRIMARY.equals(role);
    }

    public Long getDocBlueprintSourceId() {
        return docBlueprintSourceId;
    }

    public void setDocBlueprintSourceId(Long docBlueprintSourceId) {
        this.docBlueprintSourceId = docBlueprintSourceId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLinkFieldPrimary() {
        return linkFieldPrimary;
    }

    public void setLinkFieldPrimary(String linkFieldPrimary) {
        this.linkFieldPrimary = linkFieldPrimary;
    }

    public String getLinkFieldThis() {
        return linkFieldThis;
    }

    public void setLinkFieldThis(String linkFieldThis) {
        this.linkFieldThis = linkFieldThis;
    }

    public String getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(String mappingJson) {
        this.mappingJson = mappingJson;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isManaged() {
        return managed;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }
}
