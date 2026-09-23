package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One piece of user content inside a section of a document instance: either a
 * PARAGRAPH ({@code textContent}) or an IMAGE ({@code assetId} + optional
 * {@code caption}). Ordered by {@code orderIndex} within (instance, section).
 */
@Entity
@Table(name = "DocContentBlock")
public class DocContentBlock {

    public static final String TYPE_PARAGRAPH = "PARAGRAPH";
    public static final String TYPE_IMAGE = "IMAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocContentBlockId")
    private int docContentBlockId;

    @Column(name = "InstanceId")
    private int instanceId;

    @Column(name = "SectionId")
    private int sectionId;

    /** PARAGRAPH | IMAGE. */
    @Column(name = "BlockType")
    private String blockType;

    @Column(name = "OrderIndex")
    private int orderIndex;

    // Unbounded string so long paragraphs fit: text on PostgreSQL, CLOB on H2
    // (a plain @Lob would map to PostgreSQL's oid large-object type).
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "TextContent")
    private String textContent;

    @Column(name = "AssetId")
    private Integer assetId;

    @Column(name = "Caption")
    private String caption;

    public DocContentBlock() {
    }

    public int getDocContentBlockId() {
        return docContentBlockId;
    }

    public void setDocContentBlockId(int docContentBlockId) {
        this.docContentBlockId = docContentBlockId;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(int instanceId) {
        this.instanceId = instanceId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public Integer getAssetId() {
        return assetId;
    }

    public void setAssetId(Integer assetId) {
        this.assetId = assetId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }
}
