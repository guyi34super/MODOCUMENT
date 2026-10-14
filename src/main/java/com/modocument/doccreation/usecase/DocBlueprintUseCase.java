package com.modocument.doccreation.usecase;

import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.BlueprintSummaryDto;
import com.modocument.doccreation.dto.SectionDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.flow.DocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.parse.BlueprintSectionExtractor;
import com.modocument.doccreation.parse.ParsedSection;
import com.modocument.doccreation.pdf.LibreOfficeDocxConverter;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Blueprint lifecycle: upload the company's source document, break it into
 * sections through the company's flow, keep section keywords editable, and
 * guard deletion while documents are still built on it.
 */
@Service
public class DocBlueprintUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocBlueprintUseCase.class);
    private static final int MAX_KEYWORDS_LENGTH = 1000;

    private final DocBlueprintRepository blueprints;
    private final DocBlueprintSectionRepository sections;
    private final DocInstanceRepository instances;
    private final DocCreationOwnership ownership;
    private final BlueprintSectionExtractor sectionExtractor;
    private final DocumentCreationFlowRegistry flows;
    private final UploadStorage uploadStorage;
    private final FileStore files;
    private final LibreOfficeDocxConverter pdfToDocx;

    public DocBlueprintUseCase(DocBlueprintRepository blueprints,
                               DocBlueprintSectionRepository sections,
                               DocInstanceRepository instances,
                               DocCreationOwnership ownership,
                               BlueprintSectionExtractor sectionExtractor,
                               DocumentCreationFlowRegistry flows,
                               UploadStorage uploadStorage,
                               FileStore files,
                               LibreOfficeDocxConverter pdfToDocx) {
        this.blueprints = blueprints;
        this.sections = sections;
        this.instances = instances;
        this.ownership = ownership;
        this.sectionExtractor = sectionExtractor;
        this.flows = flows;
        this.uploadStorage = uploadStorage;
        this.files = files;
        this.pdfToDocx = pdfToDocx;
    }

    @Transactional
    public BlueprintDto create(MultipartFile file, String name, String companyId) {
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
                byte[] docxBytes = pdfToDocx.convertPdfToDocx(uploadStorage.localCopy(pdfStoredName));
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

        List<ParsedSection> parsed;
        String sectionSource;
        try {
            BlueprintSectionExtractor.Extraction extraction =
                    sectionExtractor.extract(uploadStorage.localCopy(storedName), blueprintName);
            parsed = flow.customizeSections(extraction.sections(), blueprint);
            sectionSource = extraction.source();
        } catch (IOException e) {
            StoredFileCleanup.deleteQuietly(files, storedKey);
            throw new java.io.UncheckedIOException("Could not read the stored blueprint.", e);
        } catch (RuntimeException e) {
            StoredFileCleanup.deleteQuietly(files, storedKey);
            throw e;
        }

        blueprint = blueprints.save(blueprint);
        List<DocBlueprintSection> saved = new java.util.ArrayList<>();
        for (ParsedSection section : parsed) {
            DocBlueprintSection entity = new DocBlueprintSection();
            entity.setBlueprintId(blueprint.getDocBlueprintId());
            entity.setBankId(blueprint.getBankId());
            entity.setOrderIndex(section.orderIndex());
            entity.setTitle(section.title());
            entity.setHeadingLevel(section.headingLevel());
            entity.setAnchorParagraphIndex(section.anchorParagraphIndex());
            entity.setAnchorText(section.anchorText());
            entity.setKeywords(joinKeywords(section.keywords()));
            saved.add(sections.save(entity));
        }
        return BlueprintDto.from(blueprint, saved, sectionSource);
    }

    public List<BlueprintSummaryDto> list() {
        List<DocBlueprint> owned = blueprints.listByOwner(ownership.requireUserId());
        if (owned.isEmpty()) {
            return List.of();
        }
        // ws28: two grouped count queries for the whole list, instead of
        // loading every blueprint's sections and counting its instances.
        List<Integer> ids = owned.stream().map(DocBlueprint::getDocBlueprintId).toList();
        java.util.Map<Integer, Long> sectionCounts = sections.countByBlueprints(ids);
        java.util.Map<Integer, Long> instanceCounts = instances.countByBlueprints(ids);
        return owned.stream()
                .map(b -> BlueprintSummaryDto.from(b,
                        sectionCounts.getOrDefault(b.getDocBlueprintId(), 0L).intValue(),
                        instanceCounts.getOrDefault(b.getDocBlueprintId(), 0L).intValue()))
                .toList();
    }

    public BlueprintDto get(int blueprintId) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        return BlueprintDto.from(blueprint, sections.listByBlueprint(blueprintId));
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

    @Transactional
    public SectionDto updateKeywords(int blueprintId, int sectionId, List<String> keywords) {
        ownership.requireBlueprint(blueprintId);
        DocBlueprintSection section = sections.getById(sectionId)
                .filter(s -> s.getBlueprintId() == blueprintId)
                .orElseThrow(() -> new NoSuchElementException("Section " + sectionId + " not found."));
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
        blueprints.delete(blueprintId);
        // Only after commit: a rollback (e.g. a racing instance create hitting
        // the FK) must not leave a live blueprint without its source file.
        StoredFileCleanup.deleteAfterCommit(files, java.util.List.of(uploadStorage.key(blueprint.getFileName())));
    }

    private static String joinKeywords(List<String> keywords) {
        String joined = String.join(",", keywords.stream()
                .map(k -> k == null ? "" : k.strip().toLowerCase(Locale.ROOT))
                .filter(k -> !k.isEmpty() && !k.contains(","))
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
