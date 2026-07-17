package com.modocument.application.port;

import com.modocument.domain.document.ExtractionSource;

import java.util.List;
import java.util.Optional;

/** Persistence port for extraction source configurations. */
public interface ExtractionSourceStore {

    ExtractionSource save(ExtractionSource source);

    Optional<ExtractionSource> getById(String id);

    List<ExtractionSource> list();

    void delete(String id);
}
