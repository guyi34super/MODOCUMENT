package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.FileStore;
import com.modocument.application.port.TemplateRepository;
import com.modocument.doccreation.dto.EditDto;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Permission;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.doccreation.dto.CreatedInstanceDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.dto.ImageUploadResultDto;
import com.modocument.doccreation.dto.InstanceDto;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.entity.DocInstanceImage;
import com.modocument.doccreation.pdftemplate.ExactnessReport;
import com.modocument.doccreation.pdftemplate.FillOptions;
import com.modocument.doccreation.pdftemplate.FillResult;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfTemplateException;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocImageAssetRepository;
import com.modocument.doccreation.repo.DocInstanceImageRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A document built on a PDF template (docs/doccreation/pdf-templates-contract.md
 * §5): its values (merged per key), its photos (uploaded once into a tray,
 * auto-filled into photo boxes or dragged onto them, swapping when a box is
 * taken), live page previews and the generated PDF — the template PDF of the
 * pinned version with the values drawn in by the engine, refused when the
 * exactness self-check fails.
 *
 * <p>Values and photos can no longer change once the document is SUBMITTED
 * or RELEASED (409): the approver reviews exactly what was submitted.
 */
@Service
public class DocInstanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocInstanceUseCase.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> VALUES = new TypeReference<>() {
    };
    private static final TypeReference<List<SourceRef>> SOURCES = new TypeReference<>() {
    };
    private static final int MAX_VALUE_LENGTH = 20_000;
    private static final int MAX_IMAGES_PER_UPLOAD = 100;

    private final DocInstanceRepository instances;
    private final DocBlueprintRepository blueprints;
    private final DocImageAssetRepository assets;
    private final DocInstanceImageRepository images;
    private final DocCreationOwnership ownership;
    private final TemplateVersions templates;
    private final PdfTemplateStore store;
    private final DocCreationSettings settings;
    private final UploadStorage uploadStorage;
    private final FileStore storage;
    private final PiiMaskingService masking;
    private final AuditRecorder audit;
    /** ws32: the names of the templates a document was filled from. */
    private final TemplateRepository templateRepository;

    public DocInstanceUseCase(DocInstanceRepository instances,
                              DocBlueprintRepository blueprints,
                              DocImageAssetRepository assets,
                              DocInstanceImageRepository images,
                              DocCreationOwnership ownership,
                              TemplateVersions templates,
                              PdfTemplateStore store,
                              DocCreationSettings settings,
                              UploadStorage uploadStorage,
                              FileStore storage,
                              PiiMaskingService masking,
                              AuditRecorder audit,
                              TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
        this.instances = instances;
        this.blueprints = blueprints;
        this.assets = assets;
        this.images = images;
        this.ownership = ownership;
        this.templates = templates;
        this.store = store;
        this.settings = settings;
        this.uploadStorage = uploadStorage;
        this.storage = storage;
        this.masking = masking;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ CRUD

    /** A new document on the template's current version; a DRAFT template is a 409. */
    @Transactional
    public CreatedInstanceDto create(int blueprintId, String title) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        requireTitle(title);
        if (TemplateVersions.isDraft(blueprint)) {
            throw new IllegalStateException("This template is still a draft; review and save it before "
                    + "creating documents from it.");
        }
        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(ownership.requireUserId());
        instance.setBankId(ownership.requireBankId());
        instance.setBlueprintId(blueprintId);
        instance.setBlueprintVersion(blueprint.getCurrentVersion());
        instance.setTitle(title.strip());
        instance.setStatus(DocInstance.STATUS_DRAFT);
        instance.setCreatedDate(LocalDateTime.now());
        instance.setUpdatedDate(instance.getCreatedDate());
        int id = instances.save(instance).getDocInstanceId();
        return new CreatedInstanceDto(id, id);
    }

    /** The caller's documents on PDF templates (documents of Word templates are not listed). */
    @Transactional(readOnly = true)
    public List<InstanceSummaryDto> list(Integer blueprintId) {
        int userId = ownership.requireUserId();
        List<DocInstance> found = blueprintId == null
                ? instances.listByOwner(userId)
                : instances.listByOwnerAndBlueprint(userId, blueprintId);
        Map<Integer, DocBlueprint> byId = blueprints.getByIds(
                found.stream().map(DocInstance::getBlueprintId).collect(Collectors.toCollection(LinkedHashSet::new)));
        return found.stream()
                .filter(i -> {
                    DocBlueprint b = byId.get(i.getBlueprintId());
                    return b != null && !b.isLegacy() && b.getOwnerUserId() == userId;
                })
                .map(i -> {
                    DocBlueprint b = byId.get(i.getBlueprintId());
                    return InstanceSummaryDto.from(i, b.getName(), b.getPageCount() == null ? 0 : b.getPageCount());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public InstanceDto get(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        return toDto(instance, templateOf(instance));
    }

    @Transactional
    public InstanceSummaryDto rename(int instanceId, String title) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireTitle(title);
        instance.setTitle(title.strip());
        touch(instance);
        return InstanceSummaryDto.from(instances.save(instance), blueprint.getName(),
                blueprint.getPageCount() == null ? 0 : blueprint.getPageCount());
    }

    @Transactional
    public void delete(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        templateOf(instance);
        int bankId = instance.getBankId();
        List<String> files = new ArrayList<>();
        for (DocImageAsset asset : assets.listByInstance(instanceId)) {
            FileStoreKeys.tryUpload(asset.getFileName()).ifPresent(files::add);
        }
        if (instance.getGeneratedPdfKey() != null) {
            files.add(instance.getGeneratedPdfKey());
        }
        files.add(FileStoreKeys.export(exportName(instanceId, "pdf")));
        images.deleteByInstance(bankId, instanceId);
        assets.deleteByInstance(instanceId);
        instances.delete(instanceId);
        // Only after commit: a rollback must not orphan the surviving rows' files.
        StoredFileCleanup.deleteAfterCommit(storage, files);
    }

    // ---------------------------------------------------------------- values

    /**
     * MERGES the given values into the stored ones: a key not sent keeps its
     * value, an empty string (or null) clears the key, an unknown key is a
     * 400. Fixes the Word path's "saving one section wipes the others" bug by
     * construction.
     */
    @Transactional
    public InstanceDto updateValues(int instanceId, Map<String, String> changes) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireEditable(instance);
        merge(instance, blueprint, changes);
        touch(instance);
        instances.save(instance);
        return toDto(instance, blueprint);
    }

    /**
     * ws32 fill from records (docs/doccreation/flow-records-contract.md §4):
     * the values merge exactly like {@link #updateValues} (unknown key 400,
     * photo field 400, length cap 400, SUBMITTED/RELEASED 409) and the
     * records used are appended to SourceRecordsJson (a record already
     * listed is replaced, ids only).
     */
    @Transactional
    public InstanceDto fillValues(int instanceId, Map<String, String> changes, List<SourceRef> sources) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireEditable(instance);
        merge(instance, blueprint, changes);
        List<SourceRef> refs = new ArrayList<>(readSources(instance));
        for (SourceRef source : sources) {
            refs.removeIf(existing -> existing.recordId() == source.recordId());
            refs.add(source);
        }
        instance.setSourceRecordsJson(refs.isEmpty() ? null : writeJson(refs));
        touch(instance);
        instances.save(instance);
        return toDto(instance, blueprint);
    }

    /**
     * {@code POST /instances/{id}/upgrade} (contract §4): moves the document
     * to the template's current version. Values keep their region key when
     * it still exists as a field; the others are dropped, and photos on
     * photo boxes that are gone go back to the tray — every such key is
     * reported in {@code droppedKeys}. A SUBMITTED or RELEASED document is a
     * 409.
     */
    @Transactional
    public InstanceDto upgrade(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireEditable(instance);
        int target = TemplateVersions.effectiveVersion(blueprint);
        Set<String> dropped = new LinkedHashSet<>();
        if (instance.getBlueprintVersion() != target) {
            List<TemplateRegion> regionList = templates.regions(blueprint, target);
            Map<String, TemplateRegion> byKey = regionList.stream()
                    .collect(Collectors.toMap(TemplateRegion::key, Function.identity(), (a, b) -> a));
            Map<String, String> values = readValues(instance);
            Map<String, String> kept = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                TemplateRegion region = byKey.get(key);
                if (region == null || region.kind() == RegionKind.IMAGE || region.kind() == RegionKind.SIGNATURE) {
                    dropped.add(key);
                } else {
                    kept.put(key, value);
                }
            });
            for (DocInstanceImage image : images.listByInstance(instance.getBankId(), instanceId)) {
                String key = image.getRegionKey();
                if (key == null) {
                    continue;
                }
                TemplateRegion region = byKey.get(key);
                if (region == null || (region.kind() != RegionKind.IMAGE && region.kind() != RegionKind.SIGNATURE)) {
                    image.setRegionKey(null);
                    images.save(image);
                    dropped.add(key);
                }
            }
            instance.setBlueprintVersion(target);
            instance.setValuesJson(writeValues(kept, regionList));
            touch(instance);
            instances.save(instance);
        }
        return toDto(instance, blueprint).withDroppedKeys(new ArrayList<>(dropped));
    }

    /** A record a document was filled from: {@code {recordId, templateId, role}}. */
    public record SourceRef(long recordId, int templateId, String role) {
    }

    /** Merges {@code changes} into the instance's values (see {@link #updateValues}); does not save. */
    private void merge(DocInstance instance, DocBlueprint blueprint, Map<String, String> changes) {
        List<TemplateRegion> regionList = templates.regions(blueprint, instance.getBlueprintVersion());
        Map<String, TemplateRegion> byKey = regionList.stream()
                .collect(Collectors.toMap(TemplateRegion::key, Function.identity(), (a, b) -> a));
        Map<String, String> values = readValues(instance);
        for (Map.Entry<String, String> change : (changes == null ? Map.<String, String>of() : changes).entrySet()) {
            String key = change.getKey();
            TemplateRegion region = byKey.get(key);
            if (region == null) {
                throw new IllegalArgumentException("Unknown field \"" + truncate(String.valueOf(key), 80) + "\".");
            }
            if (region.kind() == RegionKind.IMAGE || region.kind() == RegionKind.SIGNATURE) {
                throw new IllegalArgumentException("Field \"" + key + "\" takes a photo, not a value.");
            }
            String value = change.getValue();
            if (value == null || value.isEmpty()) {
                values.remove(key);
            } else if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("Values are limited to " + MAX_VALUE_LENGTH + " characters.");
            } else {
                values.put(key, value);
            }
        }
        instance.setValuesJson(writeValues(values, regionList));
    }

    // ---------------------------------------------------------------- photos

    /**
     * Uploads photos into the document's tray (PNG or JPEG, by content);
     * with {@code autoFill} they are then placed into the empty photo boxes
     * ({@link PhotoAutoFill}: file-name match, then reading order).
     */
    @Transactional
    public ImageUploadResultDto uploadImages(int instanceId, List<MultipartFile> files, boolean autoFill) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireEditable(instance);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded.");
        }
        if (files.size() > MAX_IMAGES_PER_UPLOAD) {
            throw new IllegalArgumentException("Upload at most " + MAX_IMAGES_PER_UPLOAD + " photos at once.");
        }
        int bankId = instance.getBankId();
        List<DocInstanceImage> existing = images.listByInstance(bankId, instanceId);
        int nextOrder = existing.stream().mapToInt(DocInstanceImage::getSortOrder).max().orElse(-1) + 1;
        List<ImageUploadResultDto.RejectedDto> rejected = new ArrayList<>();
        List<DocInstanceImage> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalName = cleanName(file.getOriginalFilename());
            String contentType;
            try {
                contentType = file.isEmpty() ? null : sniffImageContentType(file.getBytes());
            } catch (IOException e) {
                contentType = null;
            }
            if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
                rejected.add(new ImageUploadResultDto.RejectedDto(originalName,
                        "Not a readable PNG or JPEG image."));
                continue;
            }
            DocImageAsset asset = new DocImageAsset();
            asset.setInstanceId(instanceId);
            asset.setBankId(bankId);
            asset.setFileName(uploadStorage.store(file));
            // A rollback of this upload must not orphan the stored bytes.
            StoredFileCleanup.deleteOnRollback(storage, uploadStorage.key(asset.getFileName()));
            asset.setOriginalFileName(originalName);
            asset.setContentType(contentType);
            asset.setUploadedDate(LocalDateTime.now());
            asset = assets.save(asset);

            DocInstanceImage image = new DocInstanceImage();
            image.setBankId(bankId);
            image.setInstanceId(instanceId);
            image.setAssetId(asset.getDocImageAssetId());
            image.setOriginalName(originalName);
            image.setSortOrder(nextOrder++);
            uploaded.add(images.save(image));
        }

        List<ImageUploadResultDto.AssignedDto> assigned = new ArrayList<>();
        if (autoFill && !uploaded.isEmpty()) {
            List<TemplateRegion> regionList = templates.regions(blueprint, instance.getBlueprintVersion());
            Set<String> filled = existing.stream().map(DocInstanceImage::getRegionKey)
                    .filter(k -> k != null).collect(Collectors.toSet());
            Map<Integer, String> placement = PhotoAutoFill.assign(
                    uploaded.stream().map(i -> new PhotoAutoFill.Photo(i.getDocInstanceImageId(),
                            i.getOriginalName())).toList(), regionList, filled);
            for (DocInstanceImage image : uploaded) {
                String key = placement.get(image.getDocInstanceImageId());
                if (key != null) {
                    image.setRegionKey(key);
                    images.save(image);
                    assigned.add(new ImageUploadResultDto.AssignedDto(image.getDocInstanceImageId(), key));
                }
            }
        }
        List<Integer> unassigned = uploaded.stream().filter(i -> i.getRegionKey() == null)
                .map(DocInstanceImage::getDocInstanceImageId).toList();
        if (!uploaded.isEmpty()) {
            touch(instance);
            instances.save(instance);
        }
        return new ImageUploadResultDto(assigned, unassigned, rejected);
    }

    /**
     * Drag and drop: places the photo in an image region, or back in the tray
     * when {@code regionKey} is null. A region that already holds a photo
     * swaps: that photo takes this one's previous place.
     */
    @Transactional
    public InstanceDto placeImage(int instanceId, int imageId, String regionKey) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        requireEditable(instance);
        DocInstanceImage image = requireImage(instance, imageId);
        String target = regionKey == null || regionKey.isBlank() ? null : regionKey.strip();
        if (target != null) {
            TemplateRegion region = templates.regions(blueprint, instance.getBlueprintVersion()).stream()
                    .filter(r -> r.key().equals(target)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown photo box \"" + truncate(target, 80) + "\"."));
            if (region.kind() != RegionKind.IMAGE && region.kind() != RegionKind.SIGNATURE) {
                throw new IllegalArgumentException("Field \"" + target + "\" does not take a photo.");
            }
        }
        String previous = image.getRegionKey();
        if (target != null && !target.equals(previous)) {
            for (DocInstanceImage other : images.listByInstance(instance.getBankId(), instanceId)) {
                if (other.getDocInstanceImageId() != imageId && target.equals(other.getRegionKey())) {
                    other.setRegionKey(previous);
                    images.save(other);
                }
            }
        }
        image.setRegionKey(target);
        images.save(image);
        touch(instance);
        instances.save(instance);
        return toDto(instance, blueprint);
    }

    @Transactional
    public void deleteImage(int instanceId, int imageId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        templateOf(instance);
        requireEditable(instance);
        DocInstanceImage image = requireImage(instance, imageId);
        List<String> files = new ArrayList<>();
        assets.getById(image.getAssetId()).ifPresent(asset -> {
            FileStoreKeys.tryUpload(asset.getFileName()).ifPresent(files::add);
        });
        images.delete(image);
        assets.delete(image.getAssetId());
        StoredFileCleanup.deleteAfterCommit(storage, files);
        touch(instance);
        instances.save(instance);
    }

    /** The stored photo, for the builder's previews. */
    public StoredImage imageContent(int instanceId, int imageId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        templateOf(instance);
        DocInstanceImage image = requireImage(instance, imageId);
        DocImageAsset asset = assets.getById(image.getAssetId())
                .orElseThrow(() -> new NoSuchElementException("Photo " + imageId + " not found."));
        try {
            return new StoredImage(uploadStorage.readAllBytes(asset.getFileName()), asset.getContentType(),
                    image.getOriginalName());
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The photo's file is missing; upload it again.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the photo.", e);
        }
    }

    // ------------------------------------------------------------- previews

    /**
     * One page of the document as it would generate, with the values and
     * photos drawn in (not stored, no exactness check). Pages past the
     * template's are the continuation pages.
     */
    public byte[] pagePng(int instanceId, int page, Integer dpi) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        Filled filled = fill(instance, blueprint, false, false);
        int pageCount = store.engine().pages(filled.result().pdf()).size();
        if (page < 0 || page >= pageCount) {
            throw new NoSuchElementException("Page " + page + " does not exist in this document.");
        }
        return store.engine().renderPage(filled.result().pdf(), page, PdfTemplateStore.clampDpi(dpi));
    }

    // ------------------------------------------------------------ generation

    /**
     * Draws the values onto the template PDF and stores the output.
     * Deliberately NOT {@code @Transactional}: rendering and the exactness
     * check take a while and must not pin a pooled connection. A failed
     * exactness check refuses the output (500 EXACTNESS_FAILED) and is
     * audited (DOC_GENERATED, FAILED).
     */
    public GenerationResultDto generate(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = templateOf(instance);
        // REPORT channel (gate C2, PRIV-005): PII value shapes the user typed
        // are masked in the generated document when the policy says so; the
        // stored values (and the owner's preview) stay raw.
        Filled filled = fill(instance, blueprint, true, true);
        FillResult result = filled.result();
        ExactnessReport report = result.report();
        if (report != null && !report.exact()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("exact", false);
            values.put("differingPixels", report.differingPixels());
            values.put("pagesWithDiffs", report.pagesWithDiffs());
            values.put("dpi", report.dpi());
            audit.record(AuditEventSpec.builder("DOC_GENERATED", AuditOutcome.FAILED)
                    .entity("DocInstance", String.valueOf(instanceId))
                    .newValues(values)
                    .build());
            log.error("Document {}: generated PDF failed the exactness check ({} differing pixels on pages {})",
                    instanceId, report.differingPixels(), report.pagesWithDiffs());
            throw new DocCreationException(500, DocCreationException.EXACTNESS_FAILED,
                    "The generated PDF did not reproduce the template exactly and was refused. "
                            + "The template's administrator has been notified through the audit trail.");
        }
        String name = exportName(instanceId, "pdf");
        String key = FileStoreKeys.export(name);
        store.write(key, result.pdf(), "application/pdf");

        LocalDateTime now = LocalDateTime.now();
        DocInstance fresh = ownership.requireInstance(instanceId);
        // The release states (ws11) survive a regeneration: only a DRAFT moves
        // to GENERATED here.
        if (DocInstance.STATUS_DRAFT.equals(fresh.getStatus())) {
            fresh.setStatus(DocInstance.STATUS_GENERATED);
        }
        fresh.setAiComposed(false);
        fresh.setLastGeneratedDocx(null);
        fresh.setLastGeneratedPdf(name);
        fresh.setGeneratedPdfKey(key);
        fresh.setLastGeneratedDate(now);
        instances.save(fresh);
        List<String> continued = result.continuedKeys() == null ? List.of() : List.copyOf(result.continuedKeys());
        return new GenerationResultDto(true, result.addedPages(), continued, report == null || report.exact(), now);
    }

    /** The generated PDF; regenerates first when nothing was generated yet or the document changed since. */
    public PdfDownload download(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        templateOf(instance);
        if (isStale(instance)) {
            generate(instanceId);
            instance = ownership.requireInstance(instanceId);
        }
        try (InputStream in = storage.open(instance.getGeneratedPdfKey())) {
            return new PdfDownload(in.readAllBytes(), PdfTemplateStore.downloadName(instance.getTitle(), ".pdf"));
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The generated file is missing; generate the document again.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the generated file.", e);
        }
    }

    boolean isStale(DocInstance instance) {
        if (instance.getLastGeneratedDate() == null || instance.getGeneratedPdfKey() == null) {
            return true;
        }
        if (instance.getUpdatedDate() != null && instance.getUpdatedDate().isAfter(instance.getLastGeneratedDate())) {
            return true;
        }
        try {
            return !storage.exists(instance.getGeneratedPdfKey());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not check the generated file.", e);
        }
    }

    private record Filled(FillResult result) {
    }

    private Filled fill(DocInstance instance, DocBlueprint blueprint, boolean forReport, boolean verify) {
        int version = instance.getBlueprintVersion();
        List<TemplateRegion> regionList = templates.regions(blueprint, version);
        Map<String, String> values = readValues(instance);
        if (forReport) {
            Map<String, String> masked = new LinkedHashMap<>();
            values.forEach((k, v) -> masked.put(k, masking.maskReportText(v)));
            values = masked;
        }
        Map<String, byte[]> photoBytes = new HashMap<>();
        Set<String> photoKeys = regionList.stream()
                .filter(r -> r.kind() == RegionKind.IMAGE || r.kind() == RegionKind.SIGNATURE)
                .map(TemplateRegion::key).collect(Collectors.toSet());
        for (DocInstanceImage image : images.listByInstance(instance.getBankId(), instance.getDocInstanceId())) {
            if (image.getRegionKey() == null || !photoKeys.contains(image.getRegionKey())) {
                continue;
            }
            DocImageAsset asset = assets.getById(image.getAssetId()).orElse(null);
            if (asset == null) {
                continue;
            }
            try {
                photoBytes.put(image.getRegionKey(), uploadStorage.readAllBytes(asset.getFileName()));
            } catch (IOException e) {
                throw new IllegalStateException("Photo " + image.getOriginalName() + " is missing; upload it again.",
                        e);
            }
        }
        byte[] template = templates.templatePdf(blueprint, version);
        FillOptions options = new FillOptions(settings.pdfMinFontSize(), true, verify);
        try {
            return new Filled(store.engine().fill(template, regionList, values, photoBytes, options));
        } catch (PdfTemplateException e) {
            throw new IllegalStateException("The document could not be drawn: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- shared

    private InstanceDto toDto(DocInstance instance, DocBlueprint blueprint) {
        List<PageInfo> pages = TemplateVersions.pages(blueprint);
        List<TemplateRegion> regionList = templates.regions(blueprint, instance.getBlueprintVersion());
        List<RegionDto> regionDtos = regionList.stream().map(r -> OverlayMapper.toDto(r, pages, null)).toList();
        Map<String, String> values = readValues(instance);
        List<InstanceDto.ImageDto> imageDtos = images.listByInstance(instance.getBankId(), instance.getDocInstanceId())
                .stream().map(i -> new InstanceDto.ImageDto(i.getDocInstanceImageId(), i.getRegionKey(),
                        i.getOriginalName()))
                .toList();
        List<String> overflow = OverflowEstimator.overflowing(regionList, values, settings.pdfMinFontSize());
        int currentVersion = TemplateVersions.effectiveVersion(blueprint);
        List<EditDto> blueprintEdits = templates.edits(blueprint, currentVersion).stream()
                .map(e -> OverlayMapper.toDto(e, pages, null, null, true, null))
                .toList();
        boolean canEditLayout = blueprint.getOwnerUserId() == ownership.requireUserId()
                && ownership.callerHas(Permission.DOC_WRITE);
        return new InstanceDto(instance.getDocInstanceId(), instance.getTitle(), instance.getStatus(),
                blueprint.getDocBlueprintId(), blueprint.getName(), instance.getBlueprintVersion(),
                currentVersion, pages, regionDtos, values, imageDtos, overflow,
                instance.getLastGeneratedDate(), instance.getGeneratedPdfKey() != null,
                instance.getReviewComment(), sourceRecordDtos(instance), canEditLayout, blueprintEdits, null);
    }

    /** The records the document was filled from, with their template's name (null when it is gone). */
    private List<InstanceDto.SourceRecordDto> sourceRecordDtos(DocInstance instance) {
        List<SourceRef> refs = readSources(instance);
        if (refs.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> names = new HashMap<>();
        List<InstanceDto.SourceRecordDto> out = new ArrayList<>();
        for (SourceRef ref : refs) {
            String name = names.computeIfAbsent(ref.templateId(), id -> {
                try {
                    return templateRepository.getById(id).map(Template::getName).orElse(null);
                } catch (RuntimeException e) {
                    return null;
                }
            });
            out.add(new InstanceDto.SourceRecordDto(ref.recordId(), ref.templateId(), name, ref.role()));
        }
        return out;
    }

    static List<SourceRef> readSources(DocInstance instance) {
        if (instance.getSourceRecordsJson() == null || instance.getSourceRecordsJson().isBlank()) {
            return List.of();
        }
        try {
            List<SourceRef> refs = JSON.readValue(instance.getSourceRecordsJson(), SOURCES);
            return refs == null ? List.of() : refs;
        } catch (IOException e) {
            log.warn("Document {}: unreadable source records", instance.getDocInstanceId());
            return List.of();
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the document's source records.", e);
        }
    }

    /** The document's template: 410 for a document of a retired Word template. */
    private DocBlueprint templateOf(DocInstance instance) {
        DocBlueprint blueprint = ownership.requireBlueprint(instance.getBlueprintId());
        if (blueprint.isLegacy()) {
            throw DocCreationException.legacy();
        }
        return blueprint;
    }

    private DocInstanceImage requireImage(DocInstance instance, int imageId) {
        return images.getById(instance.getBankId(), imageId)
                .filter(i -> i.getInstanceId() == instance.getDocInstanceId())
                .orElseThrow(() -> new NoSuchElementException("Photo " + imageId + " not found."));
    }

    /** Values and photos are frozen while the document is under review or released. */
    private static void requireEditable(DocInstance instance) {
        if (DocInstance.STATUS_SUBMITTED.equals(instance.getStatus())) {
            throw new IllegalStateException("This document is submitted for release; it cannot change "
                    + "until the review is done.");
        }
        if (DocInstance.STATUS_RELEASED.equals(instance.getStatus())) {
            throw new IllegalStateException("This document is released and can no longer change.");
        }
    }

    public static Map<String, String> readValues(DocInstance instance) {
        if (instance.getValuesJson() == null || instance.getValuesJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, String> values = JSON.readValue(instance.getValuesJson(), VALUES);
            values.values().removeIf(v -> v == null);
            return values;
        } catch (IOException e) {
            throw new IllegalStateException("The document's values are unreadable.", e);
        }
    }

    /** Writes the values in the regions' reading order (stable JSON, readable diffs). */
    private static String writeValues(Map<String, String> values, List<TemplateRegion> regionList) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (TemplateRegion region : regionList) {
            String value = values.get(region.key());
            if (value != null) {
                ordered.put(region.key(), value);
            }
        }
        try {
            return ordered.isEmpty() ? null : JSON.writeValueAsString(ordered);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the document's values.", e);
        }
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A document title is required.");
        }
        if (title.strip().length() > 255) {
            throw new IllegalArgumentException("The title is too long (255 characters max).");
        }
    }

    private static void touch(DocInstance instance) {
        instance.setUpdatedDate(LocalDateTime.now());
    }

    private static String cleanName(String name) {
        String cleaned = name == null || name.isBlank() ? "image" : name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        cleaned = cleaned.isBlank() ? "image" : cleaned;
        return truncate(cleaned, 255);
    }

    /** The image type according to its BYTES (png, jpeg, gif, bmp), or null when unreadable. */
    static String sniffImageContentType(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream in =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            String contentType = switch (format) {
                case "png" -> "image/png";
                case "jpeg", "jpg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                default -> null;
            };
            // Confirm it actually decodes, not just that a header matched.
            return contentType != null && ImageIO.read(new ByteArrayInputStream(bytes)) != null ? contentType : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String exportName(int instanceId, String extension) {
        return "doccreation-instance" + instanceId + "." + extension;
    }

    public static String sanitizeFileName(String title) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").strip();
        return cleaned.isBlank() ? "document" : cleaned;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record StoredImage(byte[] bytes, String contentType, String originalName) {
    }

    public record PdfDownload(byte[] bytes, String downloadName) {
    }
}
