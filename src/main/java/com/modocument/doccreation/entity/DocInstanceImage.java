package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A photo of a document (V302): the stored file is the {@link DocImageAsset}
 * {@link #assetId}; the photo sits in the image region {@link #regionKey},
 * or in the tray when that is null. At most one photo per region (kept by
 * DocInstanceUseCase: placing onto a filled region swaps the two).
 */
@Entity
@Table(name = "DocInstanceImage")
public class DocInstanceImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocInstanceImageId")
    private int docInstanceImageId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "InstanceId", nullable = false)
    private int instanceId;

    @Column(name = "RegionKey", length = 64)
    private String regionKey;

    @Column(name = "AssetId", nullable = false)
    private int assetId;

    @Column(name = "SortOrder", nullable = false)
    private int sortOrder;

    @Column(name = "OriginalName", nullable = false, length = 255)
    private String originalName;

    public DocInstanceImage() {
    }

    public int getDocInstanceImageId() {
        return docInstanceImageId;
    }

    public void setDocInstanceImageId(int docInstanceImageId) {
        this.docInstanceImageId = docInstanceImageId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(int instanceId) {
        this.instanceId = instanceId;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(String regionKey) {
        this.regionKey = regionKey;
    }

    public int getAssetId() {
        return assetId;
    }

    public void setAssetId(int assetId) {
        this.assetId = assetId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }
}
