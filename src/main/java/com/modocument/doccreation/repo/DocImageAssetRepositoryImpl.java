package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocImageAsset;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocImageAssetRepositoryImpl implements DocImageAssetRepository {

    private final DocImageAssetJpaRepository jpa;

    public DocImageAssetRepositoryImpl(DocImageAssetJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocImageAsset> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<DocImageAsset> listByInstance(int instanceId) {
        return jpa.findByInstanceIdOrderByDocImageAssetId(instanceId);
    }

    @Override
    public DocImageAsset save(DocImageAsset entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }

    @Override
    public void deleteByInstance(int instanceId) {
        jpa.deleteByInstanceId(instanceId);
    }

    @Override
    public java.util.Map<Integer, List<DocImageAsset>> listByInstances(
            java.util.Collection<Integer> instanceIds) {
        java.util.Map<Integer, List<DocImageAsset>> byInstance = new java.util.LinkedHashMap<>();
        if (instanceIds.isEmpty()) {
            return byInstance;
        }
        for (DocImageAsset asset : jpa.findByInstanceIdInOrderByDocImageAssetId(instanceIds)) {
            byInstance.computeIfAbsent(asset.getInstanceId(), id -> new java.util.ArrayList<>()).add(asset);
        }
        return byInstance;
    }
}
