package com.modocument.application.port;

import com.modocument.domain.entity.ImportDetails;

import java.util.List;
import java.util.Optional;

public interface ImportDetailsRepository {

    Optional<ImportDetails> getById(int id);

    List<ImportDetails> list();

    ImportDetails add(ImportDetails entity);

    ImportDetails update(ImportDetails entity);

    void delete(int id);
}
