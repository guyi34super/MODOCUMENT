package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocContentBlock;

import java.util.List;
import java.util.Optional;

public interface DocContentBlockRepository {

    Optional<DocContentBlock> getById(int id);

    /** All blocks of an instance, ordered by section then orderIndex. */
    List<DocContentBlock> listByInstance(int instanceId);

    List<DocContentBlock> listBySection(int instanceId, int sectionId);

    Optional<DocContentBlock> findByAsset(int assetId);

    DocContentBlock save(DocContentBlock entity);

    void delete(int id);

    void deleteByInstance(int instanceId);

    /** ws28: the blocks of several instances (section, then orderIndex), keyed by instance id. */
    default java.util.Map<Integer, List<DocContentBlock>> listByInstances(
            java.util.Collection<Integer> instanceIds) {
        java.util.Map<Integer, List<DocContentBlock>> byInstance = new java.util.LinkedHashMap<>();
        for (Integer id : instanceIds) {
            byInstance.put(id, listByInstance(id));
        }
        return byInstance;
    }
}
