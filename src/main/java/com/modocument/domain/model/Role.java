package com.modocument.domain.model;

/**
 * Coarse application role of an {@code AppUser}, carried in the JWT "roles"
 * claim. Method-level enforcement (@PreAuthorize) is layered on separately;
 * this enum is only the vocabulary.
 */
public enum Role {
    ADMIN,
    APPROVER,
    READ_WRITE,
    READ_ONLY
}
