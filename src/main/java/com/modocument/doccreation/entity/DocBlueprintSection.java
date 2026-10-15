package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One heading-delimited section of a blueprint document. The anchor is the
 * body-element index of the heading paragraph in the stored (immutable) file;
 * {@code anchorParagraphIndex == -1} marks the implicit whole-document section
 * of a blueprint that has no headings at all.
 */
@Entity
@Table(name = "DocBlueprintSection")
public class DocBlueprintSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocBlueprintSectionId")
    private int docBlueprintSectionId;

    @Column(name = "BlueprintId")
    private int blueprintId;

    /** The owning root's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    @Column(name = "OrderIndex")
    private int orderIndex;

    @Column(name = "Title", length = 500)
    private String title;

    /** 1..9 from the heading outline level; 0 for the implicit section. */
    @Column(name = "HeadingLevel")
    private int headingLevel;

    @Column(name = "AnchorParagraphIndex")
    private int anchorParagraphIndex;

    /** Heading text at parse time — verified before generation to catch a changed file. */
    @Column(name = "AnchorText", length = 500)
    private String anchorText;

    /** Comma-separated, lowercase; drives image auto-assignment. Admin-editable. */
    @Column(name = "Keywords", length = 1000)
    private String keywords;

    /** The blueprint version this row belongs to (V291); older versions' rows stay for older documents. */
    @Column(name = "Version", nullable = false)
    private int version = 1;

    public DocBlueprintSection() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getDocBlueprintSectionId() {
        return docBlueprintSectionId;
    }

    public void setDocBlueprintSectionId(int docBlueprintSectionId) {
        this.docBlueprintSectionId = docBlueprintSectionId;
    }

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getHeadingLevel() {
        return headingLevel;
    }

    public void setHeadingLevel(int headingLevel) {
        this.headingLevel = headingLevel;
    }

    public int getAnchorParagraphIndex() {
        return anchorParagraphIndex;
    }

    public void setAnchorParagraphIndex(int anchorParagraphIndex) {
        this.anchorParagraphIndex = anchorParagraphIndex;
    }

    public String getAnchorText() {
        return anchorText;
    }

    public void setAnchorText(String anchorText) {
        this.anchorText = anchorText;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }
}
