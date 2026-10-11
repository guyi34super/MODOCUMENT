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

    /** ws28: the assets of several instances, keyed by instance id. */
    default java.util.Map<Integer, List<DocImageAsset>> listByInstances(
            java.util.Collection<Integer> instanceIds) {
        java.util.Map<Integer, List<DocImageAsset>> byInstance = new java.util.LinkedHashMap<>();
        for (Integer id : instanceIds) {
            byInstance.put(id, listByInstance(id));
        }
        return byInstance;
    }
}
