package com.modocument.infrastructure.persistence;

import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.ProcessStep;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ImportDetailsRepositoryImpl implements ImportDetailsRepository {

    private final ImportDetailsJpaRepository jpa;
    private final ImportHistoryJpaRepository importHistoryJpa;
    private final ProcessStepJpaRepository processStepJpa;

    public ImportDetailsRepositoryImpl(ImportDetailsJpaRepository jpa,
                                       ImportHistoryJpaRepository importHistoryJpa,
                                       ProcessStepJpaRepository processStepJpa) {
        this.jpa = jpa;
        this.importHistoryJpa = importHistoryJpa;
        this.processStepJpa = processStepJpa;
    }

    @Override
    public Optional<ImportDetails> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<ImportDetails> list() {
        return jpa.findAll();
    }

    @Override
    public List<ImportDetails> listByBankAndImportOwner(int bankId, int ownerUserId) {
        return jpa.findByBankAndImportOwner(bankId, ownerUserId);
    }

    @Override
    public ImportDetails add(ImportDetails entity) {
        // Both FK targets must exist; mirrors the original behaviour of
        // returning an empty entity instead of persisting a dangling FK.
        Optional<ImportHistory> importHistory = importHistoryJpa.findById(entity.getImportHistoryId());
        Optional<ProcessStep> processStep = processStepJpa.findById(entity.getProcessStepId());
        if (importHistory.isEmpty() || processStep.isEmpty()) {
            return new ImportDetails();
        }
        entity.setImportHistory(importHistory.get());
        entity.setProcessStep(processStep.get());
        return jpa.save(entity);
    }

    @Override
    public ImportDetails update(ImportDetails entity) {
        Optional<ImportHistory> importHistory = importHistoryJpa.findById(entity.getImportHistoryId());
        Optional<ProcessStep> processStep = processStepJpa.findById(entity.getProcessStepId());
        if (importHistory.isEmpty() || processStep.isEmpty()) {
            return new ImportDetails();
        }
        entity.setImportHistory(importHistory.get());
        entity.setProcessStep(processStep.get());
        ImportDetails saved = jpa.save(entity);
        // ws28: a merge leaves the LAZY associations uninitialized proxies;
        // the re-read fetches them, so the row serializes outside the session.
        return jpa.findById(saved.getImportDetailsId()).orElse(saved);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
