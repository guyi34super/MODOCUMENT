package com.modocument.doccreation.usecase;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.BlueprintSummaryDto;
import com.modocument.doccreation.dto.BlueprintVersionDto;
import com.modocument.doccreation.dto.EditDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.dto.SaveOverlayRequest;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintVersion;
import com.modocument.doccreation.pdftemplate.AnalysisOptions;
import com.modocument.doccreation.pdftemplate.AnalysisResult;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfTemplateException;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.pdftemplate.UploadFlow;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintVersionRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.doccreation.repo.DocTemplateEditRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import com.modocument.domain.model.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.UUID;

/**
 * Exact PDF templates (docs/doccreation/pdf-templates-contract.md): upload a
 * PDF (blank, or filled with sample data that is removed), review the
 * proposed regions and removals, save edits as new versions, preview pages,
 * download the original or a version's template PDF, delete.
 *
 * <p>Lifecycle: an upload is analysed by the {@link
 * com.modocument.doccreation.pdftemplate.PdfTemplateEngine} and stored as a
 * DRAFT whose version 1 is the PROPOSAL (CurrentVersion 0). Confirming the
 * reviewed overlay ({@code PUT …/overlay}) makes version 1 READY; every later
 * save of a READY template writes version N+1 (optimistic {@code baseVersion},
 * 409 when stale). Documents pin the version they were started from. The
 * original PDF never changes; each version has its own template PDF built by
 * {@code applyEdits} on the original.
 *
 * <p>Review data (the removed sample values) is customer data copied from
 * the upload: kept only in the draft file ({@link DraftReview}) while DRAFT
 * and deleted on confirm, never in a database row.
 */
@Service
public class DocBlueprintUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocBlueprintUseCase.class);
    private static final String PDF = "application/pdf";
    private static final int MAX_NAME_LENGTH = 255;

    private final DocBlueprintRepository blueprints;
    private final DocBlueprintVersionRepository versions;
    private final DocTemplateRegionRepository regions;
    private final DocTemplateEditRepository edits;
    private final DocInstanceRepository instances;
    private final DocCreationOwnership ownership;
    private final TemplateVersions templates;
    private final PdfTemplateStore store;
    private final DocCreationSettings settings;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public DocBlueprintUseCase(DocBlueprintRepository blueprints,
                               DocBlueprintVersionRepository versions,
                               DocTemplateRegionRepository regions,
                               DocTemplateEditRepository edits,
                               DocInstanceRepository instances,
                               DocCreationOwnership ownership,
                               TemplateVersions templates,
                               PdfTemplateStore store,
                               DocCreationSettings settings,
                               AuditRecorder audit,
                               PlatformTransactionManager transactionManager) {
        this.blueprints = blueprints;
        this.versions = versions;
        this.regions = regions;
        this.edits = edits;
        this.instances = instances;
        this.ownership = ownership;
        this.templates = templates;
        this.store = store;
        this.settings = settings;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- upload

    /**
     * Analyses the uploaded PDF and stores it as a DRAFT template with the
     * proposed regions (and, WITH_DATA, the proposed removals already applied
     * to the draft's template PDF). Deliberately not one transaction: the
     * analysis may call the model; only the writes are transactional.
     *
     * @throws DocCreationException 400 NOT_A_PDF / ENCRYPTED / TOO_MANY_PAGES / UNREADABLE
     */
    public BlueprintDto create(MultipartFile file, String name, String companyId, String flowParam) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }
        UploadFlow requested = parseFlow(flowParam);
        byte[] pdf;
        try {
            pdf = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
        if (!PdfTemplateStore.looksLikePdf(pdf)) {
            throw DocCreationException.badPdf("NOT_A_PDF",
                    "Only PDF templates are supported; this file is not a PDF.");
        }
        String originalName = file.getOriginalFilename() == null ? "template.pdf" : file.getOriginalFilename();
        String blueprintName = name == null || name.isBlank() ? baseName(originalName) : name.strip();
        blueprintName = truncate(blueprintName, MAX_NAME_LENGTH);
        String cleanCompany = companyId == null || companyId.isBlank() ? null : companyId.strip();
        if (cleanCompany != null && cleanCompany.length() > 64) {
            throw new IllegalArgumentException("companyId is too long (64 characters max).");
        }

        AnalysisResult analysis;
        byte[] draftTemplate;
        try {
            analysis = store.engine().analyze(pdf, requested,
                    new AnalysisOptions(settings.templateAiAllowed(),
                            LocaleContextHolder.getLocale().toLanguageTag()));
        } catch (PdfTemplateException e) {
            throw DocCreationException.badPdf(e.code(), describe(e));
        }
        List<PageInfo> pages = analysis.pages() == null ? List.of() : List.copyOf(analysis.pages());
        if (pages.isEmpty()) {
            throw DocCreationException.badPdf("UNREADABLE", "The PDF has no readable pages.");
        }
        UploadFlow flow = analysis.flowUsed() == UploadFlow.WITH_DATA ? UploadFlow.WITH_DATA : UploadFlow.BLANK;
        List<TemplateRegion> proposed = OverlayMapper.normalizeProposed(analysis.regions(), pages.size());
        List<TemplateEdit> removals = flow == UploadFlow.WITH_DATA
                ? OverlayMapper.normalizeProposedEdits(analysis.removals(), pages.size()) : List.of();
        try {
            draftTemplate = flow == UploadFlow.WITH_DATA
                    ? store.engine().applyEdits(pdf, removals, Map.of(), true) : pdf;
        } catch (PdfTemplateException e) {
            throw DocCreationException.badPdf(e.code(), describe(e));
        }
        String detectedBy = analysis.detectedBy() == null ? "CODE" : truncate(analysis.detectedBy(), 16);
        DraftReview review = draftReview(proposed, removals, analysis.warnings(), detectedBy);

        String finalName = blueprintName;
        DocBlueprint created = transaction.execute(status -> persistDraft(pdf, draftTemplate, finalName,
                originalName, cleanCompany, flow, pages, proposed, removals, review));
        log.info("PDF template {} '{}' uploaded: {} page(s), flow {}, {} region(s), {} removal(s), {}",
                created.getDocBlueprintId(), finalName, pages.size(), flow, proposed.size(), removals.size(),
                detectedBy);
        return toDto(created, 1);
    }

    private DocBlueprint persistDraft(byte[] pdf, byte[] draftTemplate, String name, String originalName,
                                      String companyId, UploadFlow flow, List<PageInfo> pages,
                                      List<TemplateRegion> proposed, List<TemplateEdit> removals,
                                      DraftReview review) {
        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(ownership.requireUserId());
        blueprint.setBankId(ownership.requireBankId());
        blueprint.setCompanyId(companyId);
        blueprint.setName(name);
        blueprint.setOriginalFileName(truncate(originalName, 255));
        blueprint.setFlowKey("DEFAULT");
        blueprint.setKind(DocBlueprint.KIND_PDF_OVERLAY);
        blueprint.setUploadFlow(flow.name());
        blueprint.setPageCount(pages.size());
        blueprint.setPagesJson(TemplateVersions.pagesJson(pages));
        blueprint.setCurrentVersion(0);
        blueprint.setCreatedDate(LocalDateTime.now());
        blueprint.setUpdatedDate(blueprint.getCreatedDate());
        // FileName is NOT NULL (V9); the real key is set right below.
        blueprint.setFileName("pending");
        blueprint = blueprints.save(blueprint);

        int bankId = blueprint.getBankId();
        int id = blueprint.getDocBlueprintId();
        String originalKey = PdfTemplateStore.originalKey(bankId, id);
        String templateKey = PdfTemplateStore.templateKey(bankId, id, 1);
        String draftKey = PdfTemplateStore.draftKey(bankId, id);
        storeOnce(originalKey, pdf, PDF);
        storeOnce(templateKey, draftTemplate, PDF);
        storeOnce(draftKey, review.toJson(), "application/json");
        blueprint.setOriginalFileKey(originalKey);
        blueprint.setFileName(originalKey);
        blueprint = blueprints.save(blueprint);

        saveVersionRow(blueprint, 1, review.detectedBy(), null, templateKey);
        regions.saveAll(proposed.stream().map(r -> OverlayMapper.toRow(r, bankId, id, 1)).toList());
        edits.saveAll(removals.stream().map(e -> OverlayMapper.toRow(e, bankId, id, 1)).toList());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flow", flow.name());
        values.put("pages", pages.size());
        values.put("regions", proposed.size());
        values.put("removals", removals.size());
        values.put("detectedBy", review.detectedBy());
        audit.record(AuditEventSpec.builder("DOC_BLUEPRINT_CREATED", AuditOutcome.SUCCESS)
                .entity("DocBlueprint", String.valueOf(id))
                .newValues(values)
                .build());
        return blueprint;
    }

    // ----------------------------------------------------------------- reads

    /** The caller's PDF templates (Word-based ones are not listed). */
    @Transactional(readOnly = true)
    public List<BlueprintSummaryDto> list() {
        List<DocBlueprint> owned = blueprints.listByOwnerAndKind(ownership.requireUserId(),
                DocBlueprint.KIND_PDF_OVERLAY);
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> documentCounts = instances.countByBlueprints(
                owned.stream().map(DocBlueprint::getDocBlueprintId).toList());
        return owned.stream()
                .map(b -> new BlueprintSummaryDto(b.getDocBlueprintId(), b.getName(), TemplateVersions.status(b),
                        TemplateVersions.effectiveVersion(b), b.getPageCount() == null ? 0 : b.getPageCount(),
                        b.getUploadFlow(), documentCounts.getOrDefault(b.getDocBlueprintId(), 0L).intValue(),
                        b.getCompanyId(), b.getCreatedDate()))
                .toList();
    }

    /** One version's overlay; {@code version} null means the current one (1 for a draft). */
    @Transactional(readOnly = true)
    public BlueprintDto get(int blueprintId, Integer version) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        return toDto(blueprint, TemplateVersions.resolveVersion(blueprint, version));
    }

    /** The confirmed versions, newest first. */
    @Transactional(readOnly = true)
    public List<BlueprintVersionDto> versions(int blueprintId) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        Map<Integer, Long> regionCounts = regions.countByVersion(blueprint.getBankId(), blueprintId);
        Map<Integer, Long> editCounts = edits.countByVersion(blueprint.getBankId(), blueprintId);
        Map<Integer, Long> documentCounts = instances.countByBlueprintVersion(blueprintId);
        return versions.listByBlueprint(blueprintId).stream()
                .filter(v -> v.getVersion() <= blueprint.getCurrentVersion())
                .map(v -> new BlueprintVersionDto(v.getVersion(), v.getSectionSource(),
                        regionCounts.getOrDefault(v.getVersion(), 0L).intValue(), v.getNote(),
                        v.getCreatedByUserId(), v.getCreatedDate(),
                        documentCounts.getOrDefault(v.getVersion(), 0L).intValue(),
                        v.getVersion() == blueprint.getCurrentVersion(),
                        editCounts.getOrDefault(v.getVersion(), 0L).intValue(),
                        regionCounts.getOrDefault(v.getVersion(), 0L).intValue()))
                .toList();
    }

    /**
     * One page as PNG: {@code original} renders the upload, {@code template}
     * (default) the version's template PDF. Cached by content hash.
     */
    public byte[] pagePng(int blueprintId, int page, String variant, Integer version, Integer dpi) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        int pageCount = blueprint.getPageCount() == null ? 0 : blueprint.getPageCount();
        if (page < 0 || page >= pageCount) {
            throw new NoSuchElementException("Page " + page + " does not exist in this template.");
        }
        byte[] pdf = isOriginal(variant) ? templates.originalPdf(blueprint)
                : templates.templatePdf(blueprint, TemplateVersions.resolveVersion(blueprint, version));
        return store.renderCached(blueprint.getBankId(), blueprintId, pdf, page, PdfTemplateStore.clampDpi(dpi));
    }

    /** The uploaded PDF ({@code original}) or a version's template PDF ({@code template}, default). */
    public PdfFile file(int blueprintId, String variant, Integer version) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        String base = blueprint.getOriginalFileName() == null ? blueprint.getName() : blueprint.getOriginalFileName();
        if (isOriginal(variant)) {
            return new PdfFile(templates.originalPdf(blueprint), PdfTemplateStore.downloadName(base, ".pdf"));
        }
        int shown = TemplateVersions.resolveVersion(blueprint, version);
        return new PdfFile(templates.templatePdf(blueprint, shown),
                PdfTemplateStore.downloadName(blueprint.getName() + " (template v" + shown + ")", ".pdf"));
    }

    // ----------------------------------------------------------------- edits

    /**
     * Saves the reviewed or edited overlay: rebuilds the template PDF from
     * the ORIGINAL with the edits ({@code applyEdits}; AcroForm values are
     * cleared in the WITH_DATA flow) and stores regions + edits as
     * <ul>
     *   <li>a DRAFT, {@code confirm} true (default): version 1, now READY —
     *       the review data is deleted;</li>
     *   <li>a DRAFT, {@code confirm} false: the draft's version 1, still DRAFT;</li>
     *   <li>a READY template: version N+1.</li>
     * </ul>
     *
     * @throws IllegalStateException when {@code baseVersion} is stale (409)
     * @throws IllegalArgumentException for invalid geometry, kinds or duplicate keys (400)
     */
    @Transactional
    public BlueprintDto saveOverlay(int blueprintId, SaveOverlayRequest request) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        boolean draft = TemplateVersions.isDraft(blueprint);
        requireCurrentBase(blueprint, request.baseVersion(), draft);
        List<PageInfo> pages = TemplateVersions.pages(blueprint);
        int bankId = blueprint.getBankId();
        Set<String> imageKeys = editImageKeys(blueprint);
        List<TemplateRegion> regionList = OverlayMapper.fromDtos(request.regions(), pages);
        List<TemplateEdit> editList = OverlayMapper.editsFromDtos(request.edits(), pages, imageKeys);

        Map<String, byte[]> images = new HashMap<>();
        for (TemplateEdit edit : editList) {
            if (edit.kind() == EditKind.ADD_IMAGE) {
                images.computeIfAbsent(edit.imageKey(),
                        key -> store.read(PdfTemplateStore.editImageKey(bankId, blueprintId, key)));
            }
        }
        byte[] template;
        try {
            template = editList.isEmpty() && !UploadFlow.WITH_DATA.name().equals(blueprint.getUploadFlow())
                    ? templates.originalPdf(blueprint)
                    : store.engine().applyEdits(templates.originalPdf(blueprint), editList, images,
                            UploadFlow.WITH_DATA.name().equals(blueprint.getUploadFlow()));
        } catch (PdfTemplateException e) {
            throw new IllegalArgumentException("The template edits could not be applied: " + describe(e), e);
        }

        boolean confirm = request.confirm() == null || request.confirm();
        int version = draft ? 1 : blueprint.getCurrentVersion() + 1;
        String templateKey = PdfTemplateStore.templateKey(bankId, blueprintId, version);
        storeOnce(templateKey, template, PDF);
        String note = request.note() == null || request.note().isBlank() ? null : truncate(request.note().strip(), 500);

        if (draft) {
            DocBlueprintVersion row = templates.versionRow(blueprint, 1);
            String oldKey = row.getTemplatePdfKey();
            row.setTemplatePdfKey(templateKey);
            if (note != null) {
                row.setNote(note);
            }
            versions.save(row);
            regions.deleteByBlueprintVersion(bankId, blueprintId, 1);
            edits.deleteByBlueprintVersion(bankId, blueprintId, 1);
            if (oldKey != null && !oldKey.equals(templateKey)) {
                StoredFileCleanup.deleteAfterCommit(store.files(), List.of(oldKey));
            }
            if (confirm) {
                blueprint.setCurrentVersion(1);
                StoredFileCleanup.deleteAfterCommit(store.files(),
                        List.of(PdfTemplateStore.draftKey(bankId, blueprintId)));
            }
        } else {
            blueprint.setCurrentVersion(version);
            try {
                saveVersionRow(blueprint, version, DocBlueprintVersion.SOURCE_MANUAL, note, templateKey);
            } catch (DataIntegrityViolationException e) {
                throw new IllegalStateException("This template was changed meanwhile; reload it and make "
                        + "your edits again.", e);
            }
        }
        regions.saveAll(regionList.stream().map(r -> OverlayMapper.toRow(r, bankId, blueprintId, version)).toList());
        edits.saveAll(editList.stream().map(e -> OverlayMapper.toRow(e, bankId, blueprintId, version)).toList());
        blueprint.setUpdatedDate(LocalDateTime.now());
        blueprint = blueprints.save(blueprint);

        if (!draft || confirm) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("version", version);
            values.put("regions", regionList.size());
            values.put("edits", editList.size());
            values.put("removals", editList.stream().filter(e -> e.kind() == EditKind.REMOVE).count());
            values.put("confirmedDraft", draft);
            if (note != null) {
                values.put("note", note);
            }
            audit.record(AuditEventSpec.builder("DOC_BLUEPRINT_VERSION_SAVED", AuditOutcome.SUCCESS)
                    .entity("DocBlueprint", String.valueOf(blueprintId))
                    .newValues(values)
                    .build());
        }
        log.info("PDF template {} saved as version {} ({} region(s), {} edit(s), {})", blueprintId, version,
                regionList.size(), editList.size(), TemplateVersions.status(blueprint));
        return toDto(blueprint, version);
    }

    /**
     * An image for an ADD_IMAGE template edit (a logo): PNG or JPEG, stored
     * with the template; returns the {@code imageKey} the edit names.
     */
    public String uploadEditImage(int blueprintId, MultipartFile file) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image uploaded.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded image.", e);
        }
        String type = DocInstanceUseCase.sniffImageContentType(bytes);
        if (!"image/png".equals(type) && !"image/jpeg".equals(type)) {
            throw new IllegalArgumentException("Template images must be PNG or JPEG files.");
        }
        String imageKey = "img_" + UUID.randomUUID().toString().replace("-", "");
        store.write(PdfTemplateStore.editImageKey(blueprint.getBankId(), blueprintId, imageKey), bytes, type);
        return imageKey;
    }

    @Transactional
    public void delete(int blueprintId) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        if (instances.countByBlueprint(blueprintId) > 0) {
            throw new IllegalStateException(
                    "This template still has documents built on it; delete those documents first.");
        }
        int bankId = blueprint.getBankId();
        List<String> keys = new ArrayList<>(store.listBlueprintFiles(bankId, blueprintId));
        regions.deleteByBlueprint(bankId, blueprintId);
        edits.deleteByBlueprint(bankId, blueprintId);
        versions.deleteByBlueprint(blueprintId);
        blueprints.delete(blueprintId);
        // Only after commit: a rollback (e.g. a racing document create hitting
        // the FK) must not leave a live template without its files.
        StoredFileCleanup.deleteAfterCommit(store.files(), keys);
    }

    // --------------------------------------------------------------- helpers

    private BlueprintDto toDto(DocBlueprint blueprint, int version) {
        List<PageInfo> pages = TemplateVersions.pages(blueprint);
        boolean draft = TemplateVersions.isDraft(blueprint);
        DraftReview review = draft ? loadDraft(blueprint) : DraftReview.EMPTY;
        List<RegionDto> regionDtos = templates.regions(blueprint, version).stream()
                .map(r -> OverlayMapper.toDto(r, pages, draft ? review.samples().get(r.key()) : null))
                .toList();
        List<EditDto> editDtos = templates.edits(blueprint, version).stream()
                .map(e -> OverlayMapper.toDto(e, pages,
                        draft ? review.editConfidence().get(e.id()) : null,
                        draft ? review.removed().get(e.id()) : null))
                .toList();
        String detectedBy = versions.get(blueprint.getDocBlueprintId(), 1)
                .map(DocBlueprintVersion::getSectionSource).orElse(review.detectedBy());
        return new BlueprintDto(blueprint.getDocBlueprintId(), blueprint.getName(), TemplateVersions.status(blueprint),
                blueprint.getUploadFlow(), version, TemplateVersions.effectiveVersion(blueprint), pages, regionDtos,
                editDtos, draft ? review.warnings() : List.of(), detectedBy,
                instances.countByBlueprint(blueprint.getDocBlueprintId()), blueprint.getCompanyId(),
                blueprint.getOriginalFileName(), blueprint.getCreatedDate());
    }

    private DraftReview loadDraft(DocBlueprint blueprint) {
        String key = PdfTemplateStore.draftKey(blueprint.getBankId(), blueprint.getDocBlueprintId());
        try {
            return store.exists(key) ? DraftReview.fromJson(store.read(key)) : DraftReview.EMPTY;
        } catch (RuntimeException e) {
            log.warn("Template {}: review data unreadable", blueprint.getDocBlueprintId(), e);
            return DraftReview.EMPTY;
        }
    }

    private static DraftReview draftReview(List<TemplateRegion> proposed, List<TemplateEdit> removals,
                                           List<String> warnings, String detectedBy) {
        Map<String, String> samples = new LinkedHashMap<>();
        for (TemplateRegion region : proposed) {
            if (region.sampleValue() != null && !region.sampleValue().isEmpty()) {
                samples.put(region.key(), truncate(region.sampleValue(), 2000));
            }
        }
        Map<String, String> removed = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        for (TemplateEdit edit : removals) {
            if (edit.removedText() != null && !edit.removedText().isEmpty()) {
                removed.put(edit.id(), truncate(edit.removedText(), 2000));
            }
            confidence.put(edit.id(), edit.confidence());
        }
        List<String> cleanWarnings = warnings == null ? List.of()
                : warnings.stream().filter(w -> w != null && !w.isBlank()).limit(50)
                        .map(w -> truncate(w, 500)).toList();
        return new DraftReview(samples, removed, confidence, cleanWarnings, detectedBy);
    }

    /** The image keys uploaded for this template's ADD_IMAGE edits. */
    private Set<String> editImageKeys(DocBlueprint blueprint) {
        String prefix = PdfTemplateStore.blueprintPrefix(blueprint.getBankId(), blueprint.getDocBlueprintId())
                + "edit-images/";
        Set<String> keys = new LinkedHashSet<>();
        for (String key : store.listBlueprintFiles(blueprint.getBankId(), blueprint.getDocBlueprintId())) {
            if (key.startsWith(prefix)) {
                keys.add(key.substring(prefix.length()));
            }
        }
        return keys;
    }

    private void saveVersionRow(DocBlueprint blueprint, int version, String source, String note,
                                String templateKey) {
        DocBlueprintVersion row = new DocBlueprintVersion();
        row.setBlueprintId(blueprint.getDocBlueprintId());
        row.setBankId(blueprint.getBankId());
        row.setVersion(version);
        row.setSectionSource(source);
        row.setNote(note);
        row.setTemplatePdfKey(templateKey);
        row.setCreatedByUserId(ownership.requireUserId());
        row.setCreatedDate(LocalDateTime.now());
        versions.save(row);
    }

    /** Stores a file written by this transaction and removes it again if the transaction rolls back. */
    private void storeOnce(String key, byte[] bytes, String contentType) {
        store.write(key, bytes, contentType);
        StoredFileCleanup.deleteOnRollback(store.files(), key);
    }

    /**
     * A draft accepts 0, 1 or no base version; a READY template needs the
     * current one when a base is given.
     */
    private static void requireCurrentBase(DocBlueprint blueprint, Integer baseVersion, boolean draft) {
        if (baseVersion == null) {
            return;
        }
        int current = blueprint.getCurrentVersion();
        boolean ok = draft ? baseVersion == 0 || baseVersion == 1 : baseVersion == current;
        if (!ok) {
            throw new IllegalStateException("This template was changed meanwhile (it is now version "
                    + TemplateVersions.effectiveVersion(blueprint) + "). Reload it and make your edits again.");
        }
    }

    private static UploadFlow parseFlow(String flow) {
        if (flow == null || flow.isBlank()) {
            return UploadFlow.AUTO;
        }
        try {
            return UploadFlow.valueOf(flow.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("flow must be BLANK, WITH_DATA or AUTO.");
        }
    }

    private static boolean isOriginal(String variant) {
        if (variant == null || variant.isBlank() || "template".equalsIgnoreCase(variant)) {
            return false;
        }
        if ("original".equalsIgnoreCase(variant)) {
            return true;
        }
        throw new IllegalArgumentException("variant must be original or template.");
    }

    private static String describe(PdfTemplateException e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return switch (e.code() == null ? "" : e.code()) {
            case "ENCRYPTED" -> "The PDF is password-protected; upload an unprotected copy.";
            case "TOO_MANY_PAGES" -> "The PDF has more than 500 pages.";
            case "NOT_A_PDF" -> "This file is not a PDF.";
            default -> "The PDF could not be read.";
        };
    }

    private static String baseName(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "Template" : base;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** A PDF for download. */
    public record PdfFile(byte[] bytes, String downloadName) {
    }
}
