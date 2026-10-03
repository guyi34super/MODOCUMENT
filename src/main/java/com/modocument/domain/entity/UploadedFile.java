package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Ownership record for a file in the shared uploads/ folder (V20). Every
 * stored upload gets exactly one row; endpoints that accept a stored file
 * name resolve it here and require {@code OwnerUserId} = caller. A stored
 * name without a row (pre-V20 upload) is treated as not-found — clean
 * cutover, see the migration header.
 */
@Entity
@Table(name = "UploadedFile")
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UploadedFileId")
    private int uploadedFileId;

    /** The timestamped name UploadStorage returned — unique on disk and here. */
    @Column(name = "StoredName", nullable = false, unique = true)
    private String storedName;

    /** The client-supplied name, for display only (never used on disk). */
    @Column(name = "OriginalName")
    private String originalName;

    /** The AppUser who uploaded the file — every reference is scoped to it. */
    @Column(name = "OwnerUserId", nullable = false)
    private int ownerUserId;

    /** The owner's bank, stamped on create; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "SizeBytes", nullable = false)
    private long sizeBytes;

    @Column(name = "UploadedAt", nullable = false)
    private LocalDateTime uploadedAt;

    public UploadedFile() {
    }

    public int getUploadedFileId() {
        return uploadedFileId;
    }

    public void setUploadedFileId(int uploadedFileId) {
        this.uploadedFileId = uploadedFileId;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
