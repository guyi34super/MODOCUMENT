package com.modocument.application.port;

import com.modocument.domain.document.ExtractionSource;

import java.util.List;
import java.util.Optional;

/** Persistence port for extraction source configurations. */
public interface ExtractionSourceStore {

    ExtractionSource save(ExtractionSource source);

    /** Unscoped lookup for the background worker path (no user context). */
    Optional<ExtractionSource> getById(String id);

    /** Owner-scoped lookup for user-facing reads; empty for another user's source. */
    Optional<ExtractionSource> getByIdAndOwner(String id, int ownerUserId);

    /** Unscoped listing — must never back a user-facing endpoint. */
    List<ExtractionSource> list();

    List<ExtractionSource> listByOwner(int ownerUserId);

    void delete(String id);
}
