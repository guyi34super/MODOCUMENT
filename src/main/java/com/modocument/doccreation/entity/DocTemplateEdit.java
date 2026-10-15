package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One change that turns the original PDF into a version's template PDF
 * (V302): REMOVE, REPLACE_TEXT, ADD_TEXT or ADD_IMAGE, in the same box
 * coordinates as {@link DocTemplateRegion}. The text a REMOVE took away is
 * never stored.
 */
@Entity
@Table(name = "DocTemplateEdit")
public class DocTemplateEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocTemplateEditId")
    private int docTemplateEditId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "BlueprintId", nullable = false)
    private int blueprintId;

    @Column(name = "Version", nullable = false)
    private int version;

    /** The edit's uuid, stable across versions. */
    @Column(name = "EditId", nullable = false, length = 64)
    private String editId;

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

    @Column(name = "Text", columnDefinition = "text")
    private String text;

    @Column(name = "FontName", length = 64)
    private String fontName;

    @Column(name = "FontSize")
    private Double fontSize;

    @Column(name = "Color", length = 7)
    private String color;

    @Column(name = "ImageKey", length = 128)
    private String imageKey;

    @Column(name = "Source", nullable = false, length = 16)
    private String source;

    public DocTemplateEdit() {
    }

    public int getDocTemplateEditId() {
        return docTemplateEditId;
    }

    public void setDocTemplateEditId(int docTemplateEditId) {
        this.docTemplateEditId = docTemplateEditId;
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

    public String getEditId() {
        return editId;
    }

    public void setEditId(String editId) {
        this.editId = editId;
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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        this.fontName = fontName;
    }

    public Double getFontSize() {
        return fontSize;
    }

    public void setFontSize(Double fontSize) {
        this.fontSize = fontSize;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getImageKey() {
        return imageKey;
    }

    public void setImageKey(String imageKey) {
        this.imageKey = imageKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
