package com.modocument.domain.model;

/**
 * Coarse application role of an {@code AppUser}, carried in the JWT "roles"
 * claim. Method-level enforcement (@PreAuthorize) is layered on separately;
 * this enum is only the vocabulary.
 *
 * <p>The four BANK roles come first and their declaration order is the
 * privilege order (OIDC role mapping relies on it). The two phase-2 roles
 * are appended and are never assignable as a bank user's role:
 * <ul>
 *   <li>{@link #PLATFORM_ADMIN} — MO Intelligence staff in the single
 *       platform bank (ws21); no access to any bank's customer data.</li>
 *   <li>{@link #API_CLIENT} — a machine principal of one bank (ws26), never
 *       an {@code AppUser}; its token's scopes narrow this role's grants.</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    APPROVER,
    READ_WRITE,
    READ_ONLY,
    PLATFORM_ADMIN,
    API_CLIENT;

    /** One of the four roles a customer bank's user may hold. */
    public boolean isBankRole() {
        return this == ADMIN || this == APPROVER || this == READ_WRITE || this == READ_ONLY;
    }
}
