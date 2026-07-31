package com.modocument.application.port;

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
}
