package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocInstanceRepositoryImpl implements DocInstanceRepository {

    private final DocInstanceJpaRepository jpa;

    public DocInstanceRepositoryImpl(DocInstanceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocInstance> getByIdAndOwner(int id, int ownerUserId) {
        return jpa.findByDocInstanceIdAndOwnerUserId(id, ownerUserId);
    }

    @Override
    public Optional<DocInstance> getByIdAndBank(int id, int bankId) {
        return jpa.findByDocInstanceIdAndBankId(id, bankId);
    }

    @Override
    public List<DocInstance> listByBankAndStatus(int bankId, String status) {
        return jpa.findByBankIdAndStatusOrderByDocInstanceIdDesc(bankId, status);
    }

    @Override
    public List<DocInstance> listReleasedSodViolations(int bankId) {
        return jpa.findReleasedSodViolations(bankId);
    }

    @Override
    public List<DocInstance> listByOwner(int ownerUserId) {
        return jpa.findByOwnerUserIdOrderByDocInstanceIdDesc(ownerUserId);
    }

    @Override
    public List<DocInstance> listByOwnerAndBlueprint(int ownerUserId, int blueprintId) {
        return jpa.findByOwnerUserIdAndBlueprintIdOrderByDocInstanceIdDesc(ownerUserId, blueprintId);
    }

    @Override
    public int countByBlueprint(int blueprintId) {
        return jpa.countByBlueprintId(blueprintId);
    }

    @Override
    public DocInstance save(DocInstance entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
