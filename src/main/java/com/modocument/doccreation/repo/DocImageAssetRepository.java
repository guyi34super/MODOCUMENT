package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocImageAsset;

import java.util.List;
import java.util.Optional;

public interface DocImageAssetRepository {

    Optional<DocImageAsset> getById(int id);

    List<DocImageAsset> listByInstance(int instanceId);

    DocImageAsset save(DocImageAsset entity);

    void delete(int id);

    void deleteByInstance(int instanceId);
}
