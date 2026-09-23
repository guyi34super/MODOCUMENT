package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocContentBlock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocContentBlockRepositoryImpl implements DocContentBlockRepository {

    private final DocContentBlockJpaRepository jpa;

    public DocContentBlockRepositoryImpl(DocContentBlockJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocContentBlock> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<DocContentBlock> listByInstance(int instanceId) {
        return jpa.findByInstanceIdOrderBySectionIdAscOrderIndexAsc(instanceId);
    }

    @Override
    public List<DocContentBlock> listBySection(int instanceId, int sectionId) {
        return jpa.findByInstanceIdAndSectionIdOrderByOrderIndex(instanceId, sectionId);
    }

    @Override
    public Optional<DocContentBlock> findByAsset(int assetId) {
        return jpa.findByAssetId(assetId);
    }

    @Override
    public DocContentBlock save(DocContentBlock entity) {
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
}
