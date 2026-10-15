package com.modocument.doccreation.usecase;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.BlueprintParagraphDto;
import com.modocument.doccreation.dto.BlueprintSummaryDto;
import com.modocument.doccreation.dto.BlueprintVersionDto;
import com.modocument.doccreation.dto.SaveSectionsRequest;
import com.modocument.doccreation.dto.SectionDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocBlueprintVersion;
import com.modocument.doccreation.flow.DocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.doccreation.parse.BlueprintSectionExtractor;
import com.modocument.doccreation.parse.ParsedSection;
import com.modocument.doccreation.parse.SectionCoverageValidator;
import com.modocument.doccreation.pdf.LibreOfficeDocxConverter;
import com.modocument.doccreation.pdf.PdfBlueprintReflower;
import com.modocument.doccreation.template.TemplateFieldExtractor;
import com.modocument.doccreation.template.TemplateNormalizer;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocBlueprintVersionRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.application.port.FileStore;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.file.UploadStorage;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Blueprint lifecycle: upload the company's source document, break it into
 * sections through the company's flow, keep section keywords editable, edit
 * the section structure as new versions, and guard deletion while documents
 * are still built on it.
 *
 * <p>Versions (V291): every structural change (hand edit or re-run of the AI
 * breakdown) writes a new set of section rows under the next version number.
 * Documents keep the version they were started from; new documents use the
 * blueprint's current version. The stored source file never changes.
 */
@Service
public class DocBlueprintUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocBlueprintUseCase.class);
    private static final int MAX_KEYWORDS_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_PARAGRAPH_PREVIEW = 300;
    /** Below this, gap-fill sections are inserted after AI breakdown. */
    private static final int GAP_FILL_COVERAGE_THRESHOLD = 85;

    private final DocBlueprintRepository blueprints;
    private final DocBlueprintSectionRepository sections;
    private final DocBlueprintVersionRepository versions;
    private final DocInstanceRepository instances;
    private final DocCreationOwnership ownership;
    private final BlueprintSectionExtractor sectionExtractor;
    private final DocumentCreationFlowRegistry flows;
    private final UploadStorage uploadStorage;
    private final FileStore files;
    private final PdfBlueprintReflower pdfReflower;
    private final LibreOfficeDocxConverter pdfToDocx;
    private final AuditRecorder audit;
    private final TemplateNormalizer templateNormalizer;
    private final TemplateFieldExtractor fieldExtractor;

    public DocBlueprintUseCase(DocBlueprintRepository blueprints,
                               DocBlueprintSectionRepository sections,
                               DocBlueprintVersionRepository versions,
                               DocInstanceRepository instances,
                               DocCreationOwnership ownership,
                               BlueprintSectionExtractor sectionExtractor,
                               DocumentCreationFlowRegistry flows,
                               UploadStorage uploadStorage,
                               FileStore files,
                               PdfBlueprintReflower pdfReflower,
                               LibreOfficeDocxConverter pdfToDocx,
                               AuditRecorder audit,
                               TemplateNormalizer templateNormalizer,
                               TemplateFieldExtractor fieldExtractor) {
        this.blueprints = blueprints;
        this.sections = sections;
        this.versions = versions;
        this.instances = instances;
        this.ownership = ownership;
        this.sectionExtractor = sectionExtractor;
        this.flows = flows;
        this.uploadStorage = uploadStorage;
        this.files = files;
        this.pdfReflower = pdfReflower;
        this.pdfToDocx = pdfToDocx;
        this.audit = audit;
        this.templateNormalizer = templateNormalizer;
        this.fieldExtractor = fieldExtractor;
    }

    @Transactional
    public BlueprintDto create(MultipartFile file, String name, String companyId, Boolean stripSampleData) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lowerName = originalName.toLowerCase(Locale.ROOT);
        boolean pdfUpload = lowerName.endsWith(".pdf");
        boolean docxUpload = lowerName.endsWith(".docx");
        if (!pdfUpload && !docxUpload) {
            throw new IllegalArgumentException("Only .docx and .pdf blueprints are supported.");
        }

        String storedName;
        String storedKey;
        if (docxUpload) {
            storedName = uploadStorage.store(file);
            storedKey = uploadStorage.key(storedName);
            StoredFileCleanup.deleteOnRollback(files, storedKey);
        } else {
            String pdfStoredName = uploadStorage.store(file);
            String pdfKey = uploadStorage.key(pdfStoredName);
            try {
                byte[] docxBytes = pdfAsDocx(uploadStorage.localCopy(pdfStoredName), originalName);
                String docxOriginalName = baseName(originalName) + ".docx";
                storedName = uploadStorage.store(docxOriginalName, docxBytes);
                storedKey = uploadStorage.key(storedName);
                StoredFileCleanup.deleteOnRollback(files, storedKey);
            } catch (IOException e) {
                StoredFileCleanup.deleteQuietly(files, pdfKey);
                throw new UncheckedIOException("Could not read the uploaded PDF.", e);
            } catch (RuntimeException e) {
                StoredFileCleanup.deleteQuietly(files, pdfKey);
                throw e;
            }
            StoredFileCleanup.deleteQuietly(files, pdfKey);
        }

        normalizeStoredFile(storedName, originalName, shouldStripSampleData(stripSampleData, originalName));

        String blueprintName = name == null || name.isBlank() ? baseName(originalName) : name.strip();
        if (blueprintName.length() > 255) {
            blueprintName = blueprintName.substring(0, 255);
        }
        String normalizedCompanyId = companyId == null || companyId.isBlank() ? null : companyId.strip();
        if (normalizedCompanyId != null && normalizedCompanyId.length() > 64) {
            throw new IllegalArgumentException("companyId is too long (64 characters max).");
        }
        DocumentCreationFlow flow = flows.flowFor(normalizedCompanyId);

        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(ownership.requireUserId());
        blueprint.setBankId(ownership.requireBankId());
        blueprint.setCompanyId(normalizedCompanyId);
        blueprint.setName(blueprintName);
        blueprint.setFileName(storedName);
        blueprint.setOriginalFileName(originalName);
        blueprint.setFlowKey(flow.flowKey());
        blueprint.setCreatedDate(LocalDateTime.now());
        blueprint.setUpdatedDate(blueprint.getCreatedDate());
        blueprint.setCurrentVersion(1);

        BlueprintSectionExtractor.Extraction extraction;
        try {
            extraction = extract(blueprint, flow);
        } catch (RuntimeException e) {
            StoredFileCleanup.deleteQuietly(files, storedKey);
            throw e;
        }

        blueprint = blueprints.save(blueprint);
        List<ParsedSection> sectionList = gapFillIfNeeded(blueprint, extraction.sections(), extraction.source());
        List<DocBlueprintSection> saved = enrichFormFieldSchemas(blueprint,
                saveVersion(blueprint, 1, sectionList, extraction.source(), null));
        log.info("Blueprint {} '{}' created with {} sections (source {})", blueprint.getDocBlueprintId(),
                blueprintName, saved.size(), extraction.source());
        return toDto(blueprint, saved, extraction.source(), 1);
    }

    static boolean shouldStripSampleData(Boolean userChoice, String originalFileName) {
        if (userChoice != null) {
            return userChoice;
        }
        return TemplateNormalizer.looksFilledFilename(originalFileName);
    }

    private void normalizeStoredFile(String storedName, String originalFileName, boolean strip) {
        if (!strip) {
            return;
        }
        try {
            Path local = uploadStorage.localCopy(storedName);
            byte[] raw = Files.readAllBytes(local);
            TemplateNormalizer.Result result = templateNormalizer.normalize(raw, originalFileName, true);
            if (result.clearedCount() > 0) {
                files.put(uploadStorage.key(storedName), new java.io.ByteArrayInputStream(result.bytes()),
                        result.bytes().length,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not normalize the uploaded template.", e);
        }
    }

    /**
     * The PDF rebuilt as an editable .docx: the PDFBox reflow first (real
     * paragraphs and headings the section breakdown can read), LibreOffice's
     * import as the fallback when the reflow finds no text at all.
     */
    private byte[] pdfAsDocx(Path pdf, String originalName) {
        try {
            PdfBlueprintReflower.Reflowed reflowed = pdfReflower.toDocx(pdf);
            if (!reflowed.isEmpty()) {
                log.info("PDF '{}' reflowed: {} pages ({} OCR), {} paragraphs, {} headings", originalName,
                        reflowed.pages(), reflowed.ocrPages(), reflowed.paragraphs(), reflowed.headings());
                return reflowed.docx();
            }
            log.info("PDF '{}' reflow found no text; trying the LibreOffice import", originalName);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("PDF '{}' reflow failed ({}); trying the LibreOffice import", originalName, e.getMessage());
        }
        return pdfToDocx.convertPdfToDocx(pdf);
    }

    public List<BlueprintSummaryDto> list() {
        List<DocBlueprint> owned = blueprints.listByOwner(ownership.requireUserId());
        if (owned.isEmpty()) {
            return List.of();
        }
        // ws28: two grouped count queries for the whole list, instead of
        // loading every blueprint's sections and counting its instances.
        Map<Integer, Integer> currentVersions = new LinkedHashMap<>();
        owned.forEach(b -> currentVersions.put(b.getDocBlueprintId(), b.getCurrentVersion()));
        Map<Integer, Long> sectionCounts = sections.countByBlueprintVersions(currentVersions);
        Map<Integer, Long> instanceCounts = instances.countByBlueprints(currentVersions.keySet());
        return owned.stream()
                .map(b -> BlueprintSummaryDto.from(b,
                        sectionCounts.getOrDefault(b.getDocBlueprintId(), 0L).intValue(),
                        instanceCounts.getOrDefault(b.getDocBlueprintId(), 0L).intValue()))
                .toList();
    }

    /** The current version's sections. */
    public BlueprintDto get(int blueprintId) {
        return get(blueprintId, null);
    }

    /** One version's sections; {@code version} null means the current one. */
    public BlueprintDto get(int blueprintId, Integer version) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        int shown = version == null ? blueprint.getCurrentVersion() : version;
        if (shown < 1 || shown > blueprint.getCurrentVersion()) {
            throw new NoSuchElementException("Version " + shown + " of this template does not exist.");
        }
        String source = versions.get(blueprintId, shown).map(DocBlueprintVersion::getSectionSource).orElse(null);
        List<DocBlueprintSection> sectionRows = sections.listByBlueprintVersion(blueprintId, shown);
        return toDto(blueprint, sectionRows, source, shown);
    }

    /** The version history, newest first. */
    @Transactional(readOnly = true)
    public List<BlueprintVersionDto> versions(int blueprintId) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        Map<Integer, Integer> sectionCounts = new LinkedHashMap<>();
        for (DocBlueprintSection section : sections.listByBlueprint(blueprintId)) {
            sectionCounts.merge(section.getVersion(), 1, Integer::sum);
        }
        Map<Integer, Long> documentCounts = instances.countByBlueprintVersion(blueprintId);
        return versions.listByBlueprint(blueprintId).stream()
                .map(v -> new BlueprintVersionDto(v.getVersion(), v.getSectionSource(),
                        sectionCounts.getOrDefault(v.getVersion(), 0), v.getNote(), v.getCreatedByUserId(),
                        v.getCreatedDate(), documentCounts.getOrDefault(v.getVersion(), 0L).intValue(),
                        v.getVersion() == blueprint.getCurrentVersion()))
                .toList();
    }

    /** The stored document's non-empty paragraphs, for the "add a section here" picker. */
    public List<BlueprintParagraphDto> paragraphs(int blueprintId) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        return withBody(blueprint, elements -> {
            List<BlueprintParagraphDto> out = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof XWPFParagraph paragraph)) {
                    continue;
                }
                String text = paragraphText(paragraph);
                if (text.isEmpty()) {
                    continue;
                }
                String preview = text.length() > MAX_PARAGRAPH_PREVIEW
                        ? text.substring(0, MAX_PARAGRAPH_PREVIEW) + "…" : text;
                out.add(new BlueprintParagraphDto(i, preview, paragraph.getStyle(),
                        AiSectionSplitter.isHeadingCandidate(paragraph)));
            }
            return out;
        });
    }

    /**
     * Saves a hand-edited section list as the next version. Every anchor must
     * be a real, non-empty paragraph of the stored document, used once.
     *
     * @throws IllegalStateException when {@code baseVersion} is no longer current (409)
     */
    @Transactional
    public BlueprintDto saveSections(int blueprintId, SaveSectionsRequest request) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        requireCurrentBase(blueprint, request.getBaseVersion());
        List<SaveSectionsRequest.SectionInput> inputs = request.getSections() == null
                ? List.of() : request.getSections();
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("A template needs at least one section.");
        }
        List<ParsedSection> parsed = withBody(blueprint, elements -> {
            Set<Integer> used = new HashSet<>();
            List<SaveSectionsRequest.SectionInput> sorted = new ArrayList<>(inputs);
            sorted.sort(Comparator.comparingInt(s -> s.getAnchorParagraphIndex() == null
                    ? Integer.MIN_VALUE : s.getAnchorParagraphIndex()));
            List<ParsedSection> out = new ArrayList<>();
            for (SaveSectionsRequest.SectionInput input : sorted) {
                Integer anchor = input.getAnchorParagraphIndex();
                if (anchor == null || anchor < 0 || anchor >= elements.size()
                        || !(elements.get(anchor) instanceof XWPFParagraph paragraph)) {
                    throw new IllegalArgumentException("Section \"" + safeTitle(input.getTitle())
                            + "\" does not start at a paragraph of the document.");
                }
                String anchorText = paragraphText(paragraph);
                if (anchorText.isEmpty()) {
                    throw new IllegalArgumentException("Section \"" + safeTitle(input.getTitle())
                            + "\" starts at an empty paragraph.");
                }
                if (!used.add(anchor)) {
                    throw new IllegalArgumentException("Two sections start at the same paragraph ("
                            + truncate(anchorText, 80) + ").");
                }
                String title = input.getTitle() == null ? "" : input.getTitle().strip();
                if (title.isEmpty()) {
                    throw new IllegalArgumentException("Every section needs a title.");
                }
                if (title.length() > MAX_TITLE_LENGTH) {
                    throw new IllegalArgumentException("Section titles are limited to "
                            + MAX_TITLE_LENGTH + " characters.");
                }
                int level = input.getHeadingLevel() == null ? 1 : Math.max(1, Math.min(9, input.getHeadingLevel()));
                List<String> keywords = input.getKeywords() == null ? List.of() : input.getKeywords();
                out.add(new ParsedSection(out.size(), title, level, anchor,
                        truncate(anchorText, MAX_TITLE_LENGTH), keywords));
            }
            return out;
        });
        return newVersion(blueprint, parsed, DocBlueprintVersion.SOURCE_MANUAL, request.getNote());
    }

    /**
     * Re-runs the section breakdown (AI first, heuristic fallback) on the
     * stored document and saves the result as the next version.
     */
    @Transactional
    public BlueprintDto resection(int blueprintId, Integer baseVersion, String note) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        requireCurrentBase(blueprint, baseVersion);
        BlueprintSectionExtractor.Extraction extraction =
                extract(blueprint, flows.flowFor(blueprint.getCompanyId()));
        if (extraction.sections().isEmpty()) {
            throw new IllegalStateException("The section breakdown found no sections; the template is unchanged.");
        }
        List<ParsedSection> sectionList = gapFillIfNeeded(blueprint, extraction.sections(), extraction.source());
        return newVersion(blueprint, sectionList, extraction.source(), note);
    }

    /** The stored source file, for the "download original" action. */
    public StoredFile getFile(int blueprintId) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        String rawName = blueprint.getOriginalFileName() == null || blueprint.getOriginalFileName().isBlank()
                ? blueprint.getName() + ".docx" : blueprint.getOriginalFileName();
        // The original name is browser-supplied: sanitized before it travels
        // back out in a Content-Disposition header.
        String safeName = DocInstanceUseCase.sanitizeFileName(rawName);
        try {
            return new StoredFile(uploadStorage.localCopy(blueprint.getFileName()),
                    blueprintDownloadName(safeName));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read the blueprint file.", e);
        }
    }

    /**
     * Keywords are tuned on the current version only; older versions are a
     * read-only record of what existing documents build on.
     */
    @Transactional
    public SectionDto updateKeywords(int blueprintId, int sectionId, List<String> keywords) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        DocBlueprintSection section = sections.getById(sectionId)
                .filter(s -> s.getBlueprintId() == blueprintId)
                .orElseThrow(() -> new NoSuchElementException("Section " + sectionId + " not found."));
        if (section.getVersion() != blueprint.getCurrentVersion()) {
            throw new IllegalStateException("This section belongs to an older version of the template, "
                    + "which is read-only. Edit the current version instead.");
        }
        String joined = joinKeywords(keywords == null ? List.of() : keywords);
        section.setKeywords(joined);
        return SectionDto.from(sections.save(section));
    }

    @Transactional
    public void delete(int blueprintId) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        if (instances.countByBlueprint(blueprintId) > 0) {
            throw new IllegalStateException(
                    "This blueprint still has documents built on it; delete those documents first.");
        }
        sections.deleteByBlueprint(blueprintId);
        versions.deleteByBlueprint(blueprintId);
        blueprints.delete(blueprintId);
        // Only after commit: a rollback (e.g. a racing instance create hitting
        // the FK) must not leave a live blueprint without its source file.
        StoredFileCleanup.deleteAfterCommit(files, java.util.List.of(uploadStorage.key(blueprint.getFileName())));
    }

    // ---------------------------------------------------------------- helpers

    private BlueprintSectionExtractor.Extraction extract(DocBlueprint blueprint, DocumentCreationFlow flow) {
        try {
            BlueprintSectionExtractor.Extraction extraction =
                    sectionExtractor.extract(uploadStorage.localCopy(blueprint.getFileName()), blueprint.getName());
            return new BlueprintSectionExtractor.Extraction(
                    flow.customizeSections(extraction.sections(), blueprint), extraction.source());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored blueprint.", e);
        }
    }

    private static void requireCurrentBase(DocBlueprint blueprint, Integer baseVersion) {
        if (baseVersion != null && baseVersion != blueprint.getCurrentVersion()) {
            throw new IllegalStateException("This template was changed meanwhile (it is now version "
                    + blueprint.getCurrentVersion() + "). Reload it and make your edits again.");
        }
    }

    private BlueprintDto newVersion(DocBlueprint blueprint, List<ParsedSection> parsed, String source,
                                    String note) {
        int version = blueprint.getCurrentVersion() + 1;
        blueprint.setCurrentVersion(version);
        blueprint.setUpdatedDate(LocalDateTime.now());
        blueprint = blueprints.save(blueprint);
        String cleanNote = note == null || note.isBlank() ? null : truncate(note.strip(), 500);
        List<DocBlueprintSection> saved = enrichFormFieldSchemas(blueprint,
                saveVersion(blueprint, version, parsed, source, cleanNote));
        audit.record(AuditEventSpec.builder("DOC_BLUEPRINT_VERSION_SAVED", AuditOutcome.SUCCESS)
                .entity("DocBlueprint", String.valueOf(blueprint.getDocBlueprintId()))
                .newValues(versionSummary(version, source, saved.size(), cleanNote))
                .build());
        log.info("Blueprint {} saved as version {} with {} sections (source {})",
                blueprint.getDocBlueprintId(), version, saved.size(), source);
        return toDto(blueprint, saved, source, version);
    }

    private BlueprintDto toDto(DocBlueprint blueprint, List<DocBlueprintSection> sectionRows, String source,
                               int version) {
        SectionCoverageValidator.Result coverage = coverage(blueprint, sectionRows);
        return BlueprintDto.from(blueprint, sectionRows, source, version, coverage, healthIssues(coverage, sectionRows));
    }

    private SectionCoverageValidator.Result coverage(DocBlueprint blueprint, List<DocBlueprintSection> sectionRows) {
        try (InputStream in = Files.newInputStream(uploadStorage.localCopy(blueprint.getFileName()));
             XWPFDocument doc = new XWPFDocument(in)) {
            List<IBodyElement> elements = List.copyOf(doc.getBodyElements());
            List<ParsedSection> parsed = sectionRows.stream()
                    .map(s -> new ParsedSection(s.getOrderIndex(), s.getTitle(), s.getHeadingLevel(),
                            s.getAnchorParagraphIndex(), s.getAnchorText(),
                            SectionDto.splitKeywords(s.getKeywords()), s.getSectionKind()))
                    .toList();
            return SectionCoverageValidator.compute(parsed, elements);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored blueprint.", e);
        }
    }

    private static List<String> healthIssues(SectionCoverageValidator.Result coverage,
                                             List<DocBlueprintSection> sectionRows) {
        List<String> issues = new ArrayList<>();
        if (coverage.meaningfulElements() > 0 && coverage.coveragePercent() < 100) {
            issues.add("Some parts of the document are not covered by any section — use Edit sections or Re-run AI.");
        }
        long formWithoutFields = sectionRows.stream()
                .filter(s -> ParsedSection.KIND_FORM.equals(s.getSectionKind()))
                .filter(s -> s.getFieldSchema() == null || s.getFieldSchema().isBlank())
                .count();
        if (formWithoutFields > 0) {
            issues.add(formWithoutFields + " form section(s) have no detected fields yet.");
        }
        return issues;
    }

    private List<ParsedSection> gapFillIfNeeded(DocBlueprint blueprint, List<ParsedSection> parsed, String source) {
        if (!BlueprintSectionExtractor.SOURCE_AI.equals(source) || parsed.isEmpty()) {
            return parsed;
        }
        try (InputStream in = Files.newInputStream(uploadStorage.localCopy(blueprint.getFileName()));
             XWPFDocument doc = new XWPFDocument(in)) {
            List<IBodyElement> elements = List.copyOf(doc.getBodyElements());
            SectionCoverageValidator.Result coverage = SectionCoverageValidator.compute(parsed, elements);
            if (coverage.coveragePercent() >= GAP_FILL_COVERAGE_THRESHOLD || coverage.gaps().isEmpty()) {
                log.info("Template section coverage {}% ({} meaningful elements)",
                        coverage.coveragePercent(), coverage.meaningfulElements());
                return parsed;
            }
            List<ParsedSection> filled = SectionCoverageValidator.gapFillSections(parsed, coverage.gaps(), elements);
            SectionCoverageValidator.Result after = SectionCoverageValidator.compute(filled, elements);
            log.info("Gap-filled template sections: coverage {}% → {}%", coverage.coveragePercent(),
                    after.coveragePercent());
            return filled;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored blueprint.", e);
        }
    }

    private List<DocBlueprintSection> saveVersion(DocBlueprint blueprint, int version, List<ParsedSection> parsed,
                                                  String source, String note) {
        DocBlueprintVersion row = new DocBlueprintVersion();
        row.setBlueprintId(blueprint.getDocBlueprintId());
        row.setBankId(blueprint.getBankId());
        row.setVersion(version);
        row.setSectionSource(source);
        row.setNote(note);
        row.setCreatedByUserId(ownership.requireUserId());
        row.setCreatedDate(LocalDateTime.now());
        versions.save(row);

        List<DocBlueprintSection> saved = new ArrayList<>();
        for (ParsedSection section : parsed) {
            DocBlueprintSection entity = new DocBlueprintSection();
            entity.setBlueprintId(blueprint.getDocBlueprintId());
            entity.setBankId(blueprint.getBankId());
            entity.setVersion(version);
            entity.setOrderIndex(saved.size());
            entity.setTitle(truncate(section.title(), MAX_TITLE_LENGTH));
            entity.setHeadingLevel(section.headingLevel());
            entity.setAnchorParagraphIndex(section.anchorParagraphIndex());
            entity.setAnchorText(section.anchorText() == null ? null : truncate(section.anchorText(), MAX_TITLE_LENGTH));
            entity.setKeywords(joinKeywords(section.keywords()));
            entity.setSectionKind(section.sectionKind() == null || section.sectionKind().isBlank()
                    ? ParsedSection.KIND_NARRATIVE : section.sectionKind());
            saved.add(sections.save(entity));
        }
        return saved;
    }

    private List<DocBlueprintSection> enrichFormFieldSchemas(DocBlueprint blueprint,
                                                             List<DocBlueprintSection> saved) {
        if (saved.isEmpty()) {
            return saved;
        }
        List<DocBlueprintSection> ordered = saved.stream()
                .sorted(Comparator.comparingInt(DocBlueprintSection::getAnchorParagraphIndex))
                .toList();
        return withBody(blueprint, elements -> {
            List<DocBlueprintSection> out = new ArrayList<>();
            for (DocBlueprintSection section : ordered) {
                if (!TemplateFieldExtractor.isFormSection(section)
                        || (section.getFieldSchema() != null && !section.getFieldSchema().isBlank())) {
                    out.add(section);
                    continue;
                }
                int next = TemplateFieldExtractor.nextAnchor(ordered, section);
                String schema = fieldExtractor.extractSectionFields(elements, section, next);
                if (schema != null) {
                    section.setFieldSchema(schema);
                    out.add(sections.save(section));
                } else {
                    out.add(section);
                }
            }
            out.sort(Comparator.comparingInt(DocBlueprintSection::getOrderIndex));
            return out;
        });
    }

    private static Map<String, Object> versionSummary(int version, String source, int sectionCount, String note) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", version);
        summary.put("sectionSource", source);
        summary.put("sectionCount", sectionCount);
        if (note != null) {
            summary.put("note", note);
        }
        return summary;
    }

    private <T> T withBody(DocBlueprint blueprint, java.util.function.Function<List<IBodyElement>, T> reader) {
        try (InputStream in = Files.newInputStream(uploadStorage.localCopy(blueprint.getFileName()));
             XWPFDocument doc = new XWPFDocument(in)) {
            return reader.apply(List.copyOf(doc.getBodyElements()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored blueprint.", e);
        }
    }

    private static String paragraphText(XWPFParagraph paragraph) {
        return paragraph.getText() == null ? "" : paragraph.getText().strip();
    }

    private static String safeTitle(String title) {
        return title == null ? "" : truncate(title.strip(), 80);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String joinKeywords(List<String> keywords) {
        String joined = String.join(",", keywords.stream()
                .map(k -> k == null ? "" : k.strip().toLowerCase(Locale.ROOT))
                .filter(k -> !k.isEmpty() && !k.contains(","))
                .distinct()
                .toList());
        if (joined.length() > MAX_KEYWORDS_LENGTH) {
            throw new IllegalArgumentException("Too many keywords for one section.");
        }
        return joined;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "Blueprint" : base;
    }

    /** Stored blueprints are always .docx; PDF uploads keep the PDF name in metadata. */
    static String blueprintDownloadName(String sanitizedOriginalName) {
        String lower = sanitizedOriginalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            return sanitizedOriginalName;
        }
        if (lower.endsWith(".pdf")) {
            return sanitizedOriginalName.substring(0, sanitizedOriginalName.length() - 4) + ".docx";
        }
        return sanitizedOriginalName + ".docx";
    }

    public record StoredFile(Path path, String downloadName) {
    }
}
