package com.modocument.application.port;

import com.modocument.domain.model.Role;

/**
 * The authenticated caller's identity, made available to the application layer
 * without it depending on the web/security framework. Implemented in
 * infrastructure by reading the security context.
 *
 * <p>This is the single seam through which every owner-scoped query derives
 * "whose data is this". Moving from per-user to per-company scoping later is a
 * change here plus the repositories' {@code *ByOwner} filters — not the whole
 * call graph.
 */
public interface CurrentUserProvider {

    /**
     * The current user's id (the JWT subject).
     *
     * @throws IllegalStateException when there is no authenticated user — a
     *     programming error, since every domain endpoint requires a token.
     */
    int requireUserId();

    /**
     * The current user's bank (the JWT bank_id claim), used to stamp BankId on
     * every row the user creates.
     *
     * <p>Default throws so a provider that was never taught about banks fails
     * loudly instead of silently writing into the wrong tenant; the
     * security-context implementation overrides it from the JWT.
     *
     * @throws IllegalStateException when the implementation carries no bank.
     */
    default int requireBankId() {
        throw new IllegalStateException(
                "This CurrentUserProvider supplies no bank id (token missing the bank_id claim?).");
    }

    /**
     * The current user's role (the JWT roles claim). Same contract as
     * {@link #requireBankId()}.
     */
    default Role role() {
        throw new IllegalStateException(
                "This CurrentUserProvider supplies no role (token missing the roles claim?).");
    }
}
