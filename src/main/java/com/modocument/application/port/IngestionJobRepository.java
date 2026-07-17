package com.modocument.application.port;

import com.modocument.domain.entity.IngestionJob;

import java.util.List;
import java.util.Optional;

public interface IngestionJobRepository {

    Optional<IngestionJob> getById(int id);

    List<IngestionJob> list();

    IngestionJob save(IngestionJob job);
}
