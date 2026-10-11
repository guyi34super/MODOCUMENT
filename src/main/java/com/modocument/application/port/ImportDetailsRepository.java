package com.modocument.application.port;

import com.modocument.domain.entity.ImportDetails;

import java.util.List;
import java.util.Optional;

public interface ImportDetailsRepository {

    Optional<ImportDetails> getById(int id);

    List<ImportDetails> list();

    /**
     * ws28: the details of every import {@code ownerUserId} owns in
     * {@code bankId}, with their parent import and process step — one query.
     */
    List<ImportDetails> listByBankAndImportOwner(int bankId, int ownerUserId);

    ImportDetails add(ImportDetails entity);

    ImportDetails update(ImportDetails entity);

    void delete(int id);
}
