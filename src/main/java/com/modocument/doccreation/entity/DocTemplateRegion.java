package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One fill-in region of a template version (V301): a box in PDF points in the
 * unrotated cropBox user space of page {@link #page}, origin at the cropBox's
 * lower-left corner (docs/doccreation/pdf-templates-contract.md §2). A
 * document's values are keyed by {@link #regionKey}.
 */
@Entity
@Table(name = "DocTemplateRegion")
public class DocTemplateRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocTemplateRegionId")
    private int docTemplateRegionId;

    /** The blueprint's bank; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "BlueprintId", nullable = false)
    private int blueprintId;

    @Column(name = "Version", nullable = false)
    private int version;

    @Column(name = "RegionKey", nullable = false, length = 64)
    private String regionKey;

    @Column(name = "Label", nullable = false, length = 200)
    private String label;

    @Column(name = "GroupName", length = 120)
    private String groupName;

    @Column(name = "Kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "Page", nullable = false)
    private int page;

    @Column(name = "X", nullable = false)
    private double x;

    @Column(name = "Y", nullable = false)
    private double y;

    @Column(name = "W", nullable = false)
    private double w;

    @Column(name = "H", nullable = false)
    private double h;

    @Column(name = "FontName", length = 64)
    private String fontName;

    @Column(name = "FontSize", nullable = false)
    private double fontSize;

    @Column(name = "Color", length = 7)
    private String color;

    @Column(name = "Align", nullable = false, length = 8)
    private String align = "LEFT";

    @Column(name = "MaxLines", nullable = false)
    private int maxLines = 1;

    @Column(name = "AcroFieldName", length = 255)
    private String acroFieldName;

    @Column(name = "Required", nullable = false)
    private boolean required;

    @Column(name = "SortOrder", nullable = false)
    private int sortOrder;

    @Column(name = "Source", nullable = false, length = 16)
    private String source;

    @Column(name = "Confidence", nullable = false)
    private double confidence = 1;

    public DocTemplateRegion() {
    }

    public int getDocTemplateRegionId() {
        return docTemplateRegionId;
    }

    public void setDocTemplateRegionId(int docTemplateRegionId) {
        this.docTemplateRegionId = docTemplateRegionId;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(String regionKey) {
        this.regionKey = regionKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getW() {
        return w;
    }

    public void setW(double w) {
        this.w = w;
    }

    public double getH() {
        return h;
    }

    public void setH(double h) {
        this.h = h;
    }

    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        this.fontName = fontName;
    }

    public double getFontSize() {
        return fontSize;
    }

    public void setFontSize(double fontSize) {
        this.fontSize = fontSize;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getAlign() {
        return align;
    }

    public void setAlign(String align) {
        this.align = align;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public String getAcroFieldName() {
        return acroFieldName;
    }

    public void setAcroFieldName(String acroFieldName) {
        this.acroFieldName = acroFieldName;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
