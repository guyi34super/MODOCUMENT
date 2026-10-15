package com.modocument.doccreation.usecase;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.FileStore;
import com.modocument.doccreation.dto.LegacyPurgeResultDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionJpaRepository;
import com.modocument.doccreation.repo.DocBlueprintVersionRepository;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetRepository;
import com.modocument.doccreation.repo.DocInstanceImageRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.doccreation.repo.DocTemplateEditRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import com.modocument.infrastructure.retention.AfterCommitFileDeleter;
import com.modocument.infrastructure.retention.PendingFileDeletes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one-off purge of the retired Word path (contract §5 Admin): deletes
 * the caller's bank's DOCX_LEGACY templates with every document built on them
 * (any owner), their content blocks, image assets, sections, versions and
 * stored files (the .docx, assets, generated exports). Rows go in one
 * transaction; the files after its commit, a failed delete queued for retry
 * in PendingFileDelete (AfterCommitFileDeleter, BUG-12). Audited
 * DOC_LEGACY_PURGED with the counts. Idempotent: a second run finds nothing.
 *
 * <p>Bank scope: every query names the caller's bank, and the PostgreSQL RLS
 * policy enforces the same — a bank admin purges only their own bank.
 */
@Service
public class DocLegacyPurgeUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocLegacyPurgeUseCase.class);

    private final DocBlueprintRepository blueprints;
    private final DocBlueprintVersionRepository versions;
    private final DocBlueprintSectionJpaRepository sections;
    private final DocTemplateRegionRepository regions;
    private final DocTemplateEditRepository edits;
    private final DocInstanceRepository instances;
    private final DocContentBlockJpaRepository blocks;
    private final DocImageAssetRepository assets;
    private final DocInstanceImageRepository images;
    private final DocCreationOwnership ownership;
    private final FileStore storage;
    private final PendingFileDeletes pendingDeletes;
    private final AuditRecorder audit;

    public DocLegacyPurgeUseCase(DocBlueprintRepository blueprints,
                                 DocBlueprintVersionRepository versions,
                                 DocBlueprintSectionJpaRepository sections,
                                 DocTemplateRegionRepository regions,
                                 DocTemplateEditRepository edits,
                                 DocInstanceRepository instances,
                                 DocContentBlockJpaRepository blocks,
                                 DocImageAssetRepository assets,
                                 DocInstanceImageRepository images,
                                 DocCreationOwnership ownership,
                                 FileStore storage,
                                 PendingFileDeletes pendingDeletes,
                                 AuditRecorder audit) {
        this.blueprints = blueprints;
        this.versions = versions;
        this.sections = sections;
        this.regions = regions;
        this.edits = edits;
        this.instances = instances;
        this.blocks = blocks;
        this.assets = assets;
        this.images = images;
        this.ownership = ownership;
        this.storage = storage;
        this.pendingDeletes = pendingDeletes;
        this.audit = audit;
    }

    @Transactional
    public LegacyPurgeResultDto purgeLegacy() {
        int bankId = ownership.requireBankId();
        List<DocBlueprint> legacy = blueprints.listByBankAndKind(bankId, DocBlueprint.KIND_DOCX_LEGACY);
        Set<String> files = new LinkedHashSet<>();
        int instanceCount = 0;
        for (DocBlueprint blueprint : legacy) {
            int blueprintId = blueprint.getDocBlueprintId();
            for (DocInstance instance : instances.listByBankAndBlueprint(bankId, blueprintId)) {
                int instanceId = instance.getDocInstanceId();
                for (DocImageAsset asset : assets.listByInstance(instanceId)) {
                    addKey(files, FileStoreKeys.tryUpload(asset.getFileName()), asset.getFileName());
                }
                addExport(files, instance.getLastGeneratedDocx());
                addExport(files, instance.getLastGeneratedPdf());
                if (instance.getGeneratedPdfKey() != null) {
                    files.add(instance.getGeneratedPdfKey());
                }
                blocks.deleteByInstanceId(instanceId);
                images.deleteByInstance(bankId, instanceId);
                assets.deleteByInstance(instanceId);
                instances.delete(instanceId);
                instanceCount++;
            }
            addKey(files, FileStoreKeys.tryUpload(blueprint.getFileName()), blueprint.getFileName());
            sections.deleteByBlueprintId(blueprintId);
            regions.deleteByBlueprint(bankId, blueprintId);
            edits.deleteByBlueprint(bankId, blueprintId);
            versions.deleteByBlueprint(blueprintId);
            blueprints.delete(blueprintId);
        }
        LegacyPurgeResultDto result = new LegacyPurgeResultDto(legacy.size(), instanceCount, files.size());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("blueprints", result.blueprints());
        evidence.put("instances", result.instances());
        evidence.put("files", result.files());
        audit.record(AuditEventSpec.builder("DOC_LEGACY_PURGED", AuditOutcome.SUCCESS)
                .entity("Bank", String.valueOf(bankId))
                .newValues(evidence)
                .build());
        AfterCommitFileDeleter.deleteAfterCommit(storage, new ArrayList<>(files), bankId, pendingDeletes);
        log.info("Legacy Word templates purged in bank {}: {} template(s), {} document(s), {} file(s)",
                bankId, result.blueprints(), result.instances(), result.files());
        return result;
    }

    private static void addExport(Set<String> files, String name) {
        if (name != null) {
            addKey(files, FileStoreKeys.tryExport(name), name);
        }
    }

    private static void addKey(Set<String> files, Optional<String> key, String name) {
        key.ifPresentOrElse(files::add, () -> log.warn(
                "Legacy purge skipped the file of unaddressable stored name '{}'", name));
    }
}
