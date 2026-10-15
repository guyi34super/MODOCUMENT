package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstanceImage;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DocInstanceImageRepositoryImpl implements DocInstanceImageRepository {

    private final DocInstanceImageJpaRepository jpa;

    public DocInstanceImageRepositoryImpl(DocInstanceImageJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocInstanceImage> getById(int bankId, int id) {
        return jpa.findByDocInstanceImageIdAndBankId(id, bankId);
    }

    @Override
    public List<DocInstanceImage> listByInstance(int bankId, int instanceId) {
        return jpa.findByBankIdAndInstanceIdOrderBySortOrderAscDocInstanceImageIdAsc(bankId, instanceId);
    }

    @Override
    public Map<Integer, List<DocInstanceImage>> listByInstances(int bankId, Collection<Integer> instanceIds) {
        Map<Integer, List<DocInstanceImage>> byInstance = new LinkedHashMap<>();
        if (instanceIds.isEmpty()) {
            return byInstance;
        }
        for (DocInstanceImage image : jpa.findByBankIdAndInstanceIdInOrderByInstanceIdAscSortOrderAsc(
                bankId, instanceIds)) {
            byInstance.computeIfAbsent(image.getInstanceId(), id -> new ArrayList<>()).add(image);
        }
        return byInstance;
    }

    @Override
    public DocInstanceImage save(DocInstanceImage image) {
        return jpa.save(image);
    }

    @Override
    public List<DocInstanceImage> saveAll(List<DocInstanceImage> images) {
        return jpa.saveAll(images);
    }

    @Override
    public void delete(DocInstanceImage image) {
        jpa.delete(image);
    }

    @Override
    public void deleteByInstance(int bankId, int instanceId) {
        jpa.deleteInstance(bankId, instanceId);
    }
}
