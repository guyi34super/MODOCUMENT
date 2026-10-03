package com.modocument.doccreation.usecase;

import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.BlockDto;
import com.modocument.doccreation.dto.BulkUploadResultDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.dto.InstanceBuilderDto;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.SectionDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.flow.DocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.generate.AiDocumentComposer;
import com.modocument.doccreation.generate.DocumentAssembler;
import com.modocument.doccreation.pdf.PdfConverter;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocContentBlockRepository;
import com.modocument.doccreation.repo.DocImageAssetRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything a document instance does: per-section paragraphs and images,
 * bulk image upload with flow-driven auto-assignment (Unsorted tray for the
 * rest), and generation — reproducing the blueprint exactly with the added
 * content, as .docx always and PDF when a converter is available.
 *
 * <p>Invariant kept here (single transactions): an image asset has
 * {@code assignedSectionId} set iff exactly one IMAGE block references it,
 * and that block's section matches.
 */
@Service
public class DocInstanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocInstanceUseCase.class);
    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "bmp", "image/bmp");

    private final DocInstanceRepository instances;
    private final DocBlueprintRepository blueprints;
    private final DocBlueprintSectionRepository sections;
    private final DocContentBlockRepository blocks;
    private final DocImageAssetRepository assets;
    private final DocCreationOwnership ownership;
    private final DocumentCreationFlowRegistry flows;
    private final DocumentAssembler assembler;
    private final AiDocumentComposer aiComposer;
    private final PdfConverter pdfConverter;
    private final UploadStorage uploadStorage;
    private final StorageProperties storage;

    public DocInstanceUseCase(DocInstanceRepository instances,
                              DocBlueprintRepository blueprints,
                              DocBlueprintSectionRepository sections,
                              DocContentBlockRepository blocks,
                              DocImageAssetRepository assets,
                              DocCreationOwnership ownership,
                              DocumentCreationFlowRegistry flows,
                              DocumentAssembler assembler,
                              AiDocumentComposer aiComposer,
                              PdfConverter pdfConverter,
                              UploadStorage uploadStorage,
                              StorageProperties storage) {
        this.instances = instances;
        this.blueprints = blueprints;
        this.sections = sections;
        this.blocks = blocks;
        this.assets = assets;
        this.ownership = ownership;
        this.flows = flows;
        this.assembler = assembler;
        this.aiComposer = aiComposer;
        this.pdfConverter = pdfConverter;
        this.uploadStorage = uploadStorage;
        this.storage = storage;
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public InstanceSummaryDto create(int blueprintId, String title) {
        DocBlueprint blueprint = ownership.requireBlueprint(blueprintId);
        requireTitle(title);
        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(ownership.requireUserId());
        instance.setBankId(ownership.requireBankId());
        instance.setBlueprintId(blueprintId);
        instance.setTitle(title.strip());
        instance.setStatus(DocInstance.STATUS_DRAFT);
        instance.setCreatedDate(LocalDateTime.now());
        instance.setUpdatedDate(instance.getCreatedDate());
        return InstanceSummaryDto.from(instances.save(instance), blueprint.getName());
    }

    public List<InstanceSummaryDto> list(Integer blueprintId) {
        int userId = ownership.requireUserId();
        List<DocInstance> found = blueprintId == null
                ? instances.listByOwner(userId)
                : instances.listByOwnerAndBlueprint(userId, blueprintId);
        Map<Integer, String> blueprintNames = new HashMap<>();
        return found.stream()
                .map(i -> InstanceSummaryDto.from(i, blueprintNames.computeIfAbsent(i.getBlueprintId(),
                        id -> blueprints.getByIdAndOwner(id, userId).map(DocBlueprint::getName).orElse(""))))
                .toList();
    }

    public InstanceBuilderDto getBuilder(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = ownership.requireBlueprint(instance.getBlueprintId());
        List<DocBlueprintSection> sectionList = sections.listByBlueprint(blueprint.getDocBlueprintId());
        Map<Integer, List<DocContentBlock>> blocksBySection = blocks.listByInstance(instanceId).stream()
                .collect(Collectors.groupingBy(DocContentBlock::getSectionId));
        Map<Integer, DocImageAsset> assetsById = assets.listByInstance(instanceId).stream()
                .collect(Collectors.toMap(DocImageAsset::getDocImageAssetId, Function.identity()));

        List<InstanceBuilderDto.BuilderSectionDto> sectionDtos = sectionList.stream()
                .map(s -> {
                    SectionDto base = SectionDto.from(s);
                    List<BlockDto> blockDtos = blocksBySection
                            .getOrDefault(s.getDocBlueprintSectionId(), List.of()).stream()
                            .sorted(java.util.Comparator.comparingInt(DocContentBlock::getOrderIndex))
                            .map(b -> BlockDto.from(b, b.getAssetId() == null ? null
                                    : Optional.ofNullable(assetsById.get(b.getAssetId()))
                                            .map(DocImageAsset::getOriginalFileName).orElse(null)))
                            .toList();
                    return new InstanceBuilderDto.BuilderSectionDto(base.sectionId(), base.orderIndex(),
                            base.title(), base.headingLevel(), base.keywords(), blockDtos);
                })
                .toList();
        List<InstanceBuilderDto.UnsortedAssetDto> unsorted = assets.listByInstance(instanceId).stream()
                .filter(a -> a.getAssignedSectionId() == null)
                .map(a -> new InstanceBuilderDto.UnsortedAssetDto(a.getDocImageAssetId(), a.getOriginalFileName()))
                .toList();
        return new InstanceBuilderDto(instance.getDocInstanceId(), instance.getTitle(),
                blueprint.getDocBlueprintId(), blueprint.getName(), instance.getStatus(),
                instance.getLastGeneratedDate(), pdfConverter.isAvailable(), sectionDtos, unsorted);
    }

    @Transactional
    public InstanceSummaryDto rename(int instanceId, String title) {
        DocInstance instance = ownership.requireInstance(instanceId);
        requireTitle(title);
        instance.setTitle(title.strip());
        touch(instance);
        DocBlueprint blueprint = ownership.requireBlueprint(instance.getBlueprintId());
        return InstanceSummaryDto.from(instances.save(instance), blueprint.getName());
    }

    @Transactional
    public void delete(int instanceId) {
        ownership.requireInstance(instanceId);
        List<DocImageAsset> assetList = assets.listByInstance(instanceId);
        blocks.deleteByInstance(instanceId);
        assets.deleteByInstance(instanceId);
        instances.delete(instanceId);
        List<Path> files = new ArrayList<>();
        for (DocImageAsset asset : assetList) {
            files.add(uploadStorage.resolve(asset.getFileName()));
        }
        files.add(storage.exportsDir().resolve(exportName(instanceId, "docx")));
        files.add(storage.exportsDir().resolve(exportName(instanceId, "pdf")));
        // Only after commit: a rollback must not orphan the surviving rows'
        // files.
        StoredFileCleanup.deleteAfterCommit(files);
    }

    // ---------------------------------------------------------------- blocks

    @Transactional
    public BlockDto addBlock(int instanceId, int sectionId, AddBlockRequest request) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprintSection section = requireSectionOf(instance, sectionId);

        DocContentBlock block = new DocContentBlock();
        block.setInstanceId(instanceId);
        block.setBankId(instance.getBankId());
        block.setSectionId(section.getDocBlueprintSectionId());
        block.setOrderIndex(nextOrderIndex(instanceId, sectionId));

        String assetFileName = null;
        if (DocContentBlock.TYPE_PARAGRAPH.equals(request.getBlockType())) {
            block.setBlockType(DocContentBlock.TYPE_PARAGRAPH);
            block.setTextContent(request.getText() == null ? "" : request.getText());
        } else if (DocContentBlock.TYPE_IMAGE.equals(request.getBlockType())) {
            if (request.getAssetId() == null) {
                throw new IllegalArgumentException("An image block needs an assetId.");
            }
            DocImageAsset asset = requireAssetOf(instanceId, request.getAssetId());
            if (asset.getAssignedSectionId() != null) {
                throw new IllegalArgumentException(
                        "Image " + asset.getOriginalFileName() + " is already placed in a section.");
            }
            block.setBlockType(DocContentBlock.TYPE_IMAGE);
            block.setAssetId(asset.getDocImageAssetId());
            block.setCaption(requireCaption(request.getCaption()));
            asset.setAssignedSectionId(sectionId);
            assets.save(asset);
            assetFileName = asset.getOriginalFileName();
        } else {
            throw new IllegalArgumentException("blockType must be PARAGRAPH or IMAGE.");
        }
        touch(instance);
        instances.save(instance);
        return BlockDto.from(blocks.save(block), assetFileName);
    }

    @Transactional
    public BlockDto updateBlock(int blockId, String text, String caption) {
        DocContentBlock block = requireBlock(blockId);
        DocInstance instance = ownership.requireInstance(block.getInstanceId());
        String assetFileName = null;
        if (DocContentBlock.TYPE_PARAGRAPH.equals(block.getBlockType())) {
            if (text != null) {
                block.setTextContent(text);
            }
        } else {
            if (caption != null) {
                block.setCaption(requireCaption(caption));
            }
            assetFileName = block.getAssetId() == null ? null
                    : assets.getById(block.getAssetId()).map(DocImageAsset::getOriginalFileName).orElse(null);
        }
        touch(instance);
        instances.save(instance);
        return BlockDto.from(blocks.save(block), assetFileName);
    }

    @Transactional
    public void reorderBlocks(int instanceId, int sectionId, List<Integer> blockIds) {
        DocInstance instance = ownership.requireInstance(instanceId);
        requireSectionOf(instance, sectionId);
        List<DocContentBlock> current = blocks.listBySection(instanceId, sectionId);
        Set<Integer> currentIds = current.stream()
                .map(DocContentBlock::getDocContentBlockId).collect(Collectors.toSet());
        if (blockIds == null || blockIds.size() != currentIds.size()
                || !currentIds.equals(new HashSet<>(blockIds))) {
            throw new IllegalArgumentException("blockIds must be a permutation of the section's blocks.");
        }
        Map<Integer, DocContentBlock> byId = current.stream()
                .collect(Collectors.toMap(DocContentBlock::getDocContentBlockId, Function.identity()));
        for (int i = 0; i < blockIds.size(); i++) {
            DocContentBlock block = byId.get(blockIds.get(i));
            block.setOrderIndex(i);
            blocks.save(block);
        }
        touch(instance);
        instances.save(instance);
    }

    @Transactional
    public void deleteBlock(int blockId) {
        DocContentBlock block = requireBlock(blockId);
        DocInstance instance = ownership.requireInstance(block.getInstanceId());
        if (block.getAssetId() != null) {
            // The image itself survives: back to the Unsorted tray.
            assets.getById(block.getAssetId()).ifPresent(asset -> {
                asset.setAssignedSectionId(null);
                assets.save(asset);
            });
        }
        blocks.delete(blockId);
        touch(instance);
        instances.save(instance);
    }

    // ---------------------------------------------------------------- assets

    @Transactional
    public BulkUploadResultDto uploadAssets(int instanceId, List<MultipartFile> files, boolean autoAssign) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = ownership.requireBlueprint(instance.getBlueprintId());
        List<DocBlueprintSection> sectionList = sections.listByBlueprint(blueprint.getDocBlueprintId());
        DocumentCreationFlow flow = flows.flowFor(blueprint.getCompanyId());

        List<BulkUploadResultDto.AssignedDto> assigned = new ArrayList<>();
        List<BulkUploadResultDto.UnsortedDto> unsorted = new ArrayList<>();
        List<BulkUploadResultDto.RejectedDto> rejected = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
            if (contentTypeFor(originalName) == null) {
                rejected.add(new BulkUploadResultDto.RejectedDto(originalName,
                        "Unsupported image type; use png, jpg, jpeg, gif or bmp."));
                continue;
            }
            // The stored content type comes from the BYTES, not the file name:
            // a PNG renamed .jpg would otherwise be embedded with the wrong
            // OOXML picture type and can break PDF conversion.
            String contentType;
            try {
                contentType = file.isEmpty() ? null : sniffImageContentType(file.getBytes());
            } catch (IOException e) {
                contentType = null;
            }
            if (contentType == null) {
                rejected.add(new BulkUploadResultDto.RejectedDto(originalName, "Not a readable image."));
                continue;
            }

            DocImageAsset asset = new DocImageAsset();
            asset.setInstanceId(instanceId);
            asset.setBankId(instance.getBankId());
            asset.setFileName(uploadStorage.store(file));
            // A rollback of this bulk upload must not orphan the stored bytes.
            StoredFileCleanup.deleteOnRollback(uploadStorage.resolve(asset.getFileName()));
            asset.setOriginalFileName(originalName);
            asset.setContentType(contentType);
            asset.setUploadedDate(LocalDateTime.now());
            asset = assets.save(asset);

            Optional<Integer> sectionId = autoAssign
                    ? flow.assignSection(originalName, sectionList) : Optional.empty();
            if (sectionId.isPresent()) {
                DocContentBlock block = new DocContentBlock();
                block.setInstanceId(instanceId);
                block.setBankId(instance.getBankId());
                block.setSectionId(sectionId.get());
                block.setBlockType(DocContentBlock.TYPE_IMAGE);
                block.setOrderIndex(nextOrderIndex(instanceId, sectionId.get()));
                block.setAssetId(asset.getDocImageAssetId());
                block = blocks.save(block);
                asset.setAssignedSectionId(sectionId.get());
                assets.save(asset);
                assigned.add(new BulkUploadResultDto.AssignedDto(asset.getDocImageAssetId(), originalName,
                        sectionId.get(), block.getDocContentBlockId()));
            } else {
                unsorted.add(new BulkUploadResultDto.UnsortedDto(asset.getDocImageAssetId(), originalName));
            }
        }
        touch(instance);
        instances.save(instance);
        return new BulkUploadResultDto(assigned, unsorted, rejected);
    }

    /** The stored image, for authenticated previews in the builder. */
    public StoredAsset getAssetContent(int assetId) {
        DocImageAsset asset = requireAsset(assetId);
        return new StoredAsset(uploadStorage.resolve(asset.getFileName()), asset.getContentType(),
                asset.getOriginalFileName());
    }

    /** Moves an image into a section (replacing its previous placement) or back to Unsorted. */
    @Transactional
    public void assignAsset(int assetId, Integer sectionId) {
        DocImageAsset asset = requireAsset(assetId);
        DocInstance instance = ownership.requireInstance(asset.getInstanceId());
        if (sectionId != null) {
            requireSectionOf(instance, sectionId);
        }
        Optional<DocContentBlock> existing = blocks.findByAsset(assetId);
        if (sectionId == null) {
            existing.ifPresent(b -> blocks.delete(b.getDocContentBlockId()));
            asset.setAssignedSectionId(null);
        } else if (existing.isPresent()) {
            DocContentBlock block = existing.get();
            if (block.getSectionId() != sectionId) {
                block.setSectionId(sectionId);
                block.setOrderIndex(nextOrderIndex(asset.getInstanceId(), sectionId));
                blocks.save(block);
            }
            asset.setAssignedSectionId(sectionId);
        } else {
            DocContentBlock block = new DocContentBlock();
            block.setInstanceId(asset.getInstanceId());
            block.setBankId(instance.getBankId());
            block.setSectionId(sectionId);
            block.setBlockType(DocContentBlock.TYPE_IMAGE);
            block.setOrderIndex(nextOrderIndex(asset.getInstanceId(), sectionId));
            block.setAssetId(assetId);
            blocks.save(block);
            asset.setAssignedSectionId(sectionId);
        }
        assets.save(asset);
        touch(instance);
        instances.save(instance);
    }

    @Transactional
    public void deleteAsset(int assetId) {
        DocImageAsset asset = requireAsset(assetId);
        DocInstance instance = ownership.requireInstance(asset.getInstanceId());
        blocks.findByAsset(assetId).ifPresent(b -> blocks.delete(b.getDocContentBlockId()));
        assets.delete(assetId);
        StoredFileCleanup.deleteAfterCommit(List.of(uploadStorage.resolve(asset.getFileName())));
        touch(instance);
        instances.save(instance);
    }

    // ------------------------------------------------------------ generation

    /**
     * Deliberately NOT {@code @Transactional}: composing (an LLM call) and PDF
     * conversion (LibreOffice) can take minutes, and pinning a pooled DB
     * connection across them exhausts the pool under a handful of concurrent
     * generations. Every read stands alone and the only write is the final
     * status save.
     */
    public GenerationResultDto generate(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = ownership.requireBlueprint(instance.getBlueprintId());
        List<DocBlueprintSection> sectionList = sections.listByBlueprint(blueprint.getDocBlueprintId());
        Map<Integer, List<DocContentBlock>> blocksBySection = blocks.listByInstance(instanceId).stream()
                .collect(Collectors.groupingBy(DocContentBlock::getSectionId));
        Map<Integer, DocImageAsset> assetsById = assets.listByInstance(instanceId).stream()
                .collect(Collectors.toMap(DocImageAsset::getDocImageAssetId, Function.identity()));

        Path blueprintPath = requireBlueprintFile(blueprint);
        Function<Integer, byte[]> assetLoader = assetBytesLoader(assetsById);
        // AI composes the final document from the template + all the user's
        // content (validated to reproduce the template exactly); the
        // deterministic engine carries generation whenever AI can't.
        String composedBy = "AI";
        byte[] docxBytes = aiComposer
                .compose(blueprintPath, sectionList, blocksBySection, assetsById, assetLoader)
                .orElse(null);
        if (docxBytes == null) {
            composedBy = "ENGINE";
            docxBytes = assembler.assemble(blueprintPath, sectionList, blocksBySection,
                    assetsById, assetLoader);
        }
        docxBytes = decorate(docxBytes, blueprint, instance);

        try {
            Files.createDirectories(storage.exportsDir());
            String docxName = exportName(instanceId, "docx");
            Path docxPath = storage.exportsDir().resolve(docxName);
            writeAtomically(docxPath, docxBytes);

            String pdfName = null;
            String pdfError = null;
            try {
                byte[] pdfBytes = pdfConverter.convert(docxPath);
                pdfName = exportName(instanceId, "pdf");
                writeAtomically(storage.exportsDir().resolve(pdfName), pdfBytes);
            } catch (IllegalStateException e) {
                pdfError = e.getMessage();
            }

            LocalDateTime now = LocalDateTime.now();
            instance.setStatus(DocInstance.STATUS_GENERATED);
            instance.setLastGeneratedDocx(docxName);
            instance.setLastGeneratedPdf(pdfName);
            instance.setLastGeneratedDate(now);
            instances.save(instance);
            return new GenerationResultDto(docxName, pdfName, pdfName != null, pdfError, now, composedBy);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the generated document.", e);
        }
    }

    /**
     * The generated file for download; regenerates first when nothing was
     * generated yet or the content changed since (GET-safe: a re-download just
     * overwrites the same export files).
     */
    public DownloadFile download(int instanceId, String format) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"docx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("format must be docx or pdf.");
        }
        DocInstance instance = ownership.requireInstance(instanceId);
        if (pdf && !pdfConverter.isAvailable()) {
            throw new IllegalStateException("PDF conversion is not available on this server "
                    + "(LibreOffice is not installed); download the .docx instead.");
        }
        if (isStale(instance, pdf)) {
            generate(instanceId);
            instance = ownership.requireInstance(instanceId);
        }
        String fileName = pdf ? instance.getLastGeneratedPdf() : instance.getLastGeneratedDocx();
        if (fileName == null) {
            throw new IllegalStateException("PDF conversion failed; download the .docx instead.");
        }
        Path path = storage.exportsDir().resolve(fileName);
        if (!Files.exists(path)) {
            throw new IllegalStateException("The generated file is missing; generate the document again.");
        }
        return new DownloadFile(path, sanitizeFileName(instance.getTitle()) + (pdf ? ".pdf" : ".docx"));
    }

    private boolean isStale(DocInstance instance, boolean pdf) {
        if (instance.getLastGeneratedDate() == null || instance.getLastGeneratedDocx() == null) {
            return true;
        }
        if (pdf && instance.getLastGeneratedPdf() == null) {
            return true; // generated before the converter was available
        }
        if (instance.getUpdatedDate() != null
                && instance.getUpdatedDate().isAfter(instance.getLastGeneratedDate())) {
            return true;
        }
        String fileName = pdf ? instance.getLastGeneratedPdf() : instance.getLastGeneratedDocx();
        return !Files.exists(storage.exportsDir().resolve(fileName));
    }

    /** The company flow's last touch on the assembled document (stamps, footers, ...). */
    private byte[] decorate(byte[] docxBytes, DocBlueprint blueprint, DocInstance instance) {
        DocumentCreationFlow flow = flows.flowFor(blueprint.getCompanyId());
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(docxBytes))) {
            flow.decorateGeneratedDocument(doc, instance, blueprint);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not finalize the generated document.", e);
        }
    }

    /**
     * Exports live at fixed per-instance paths that downloads stream while
     * regenerations overwrite; an atomic move means a reader always sees a
     * complete file, never a truncated one.
     */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path requireBlueprintFile(DocBlueprint blueprint) {
        Path blueprintPath = uploadStorage.resolve(blueprint.getFileName());
        if (!Files.exists(blueprintPath)) {
            throw new IllegalStateException("The blueprint's source file is missing; re-upload the blueprint.");
        }
        return blueprintPath;
    }

    private Function<Integer, byte[]> assetBytesLoader(Map<Integer, DocImageAsset> assetsById) {
        return assetId -> {
            DocImageAsset asset = assetsById.get(assetId);
            try {
                return Files.readAllBytes(uploadStorage.resolve(asset.getFileName()));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Image " + asset.getOriginalFileName() + " is missing; upload it again.", e);
            }
        };
    }

    // ---------------------------------------------------------------- shared

    private DocBlueprintSection requireSectionOf(DocInstance instance, int sectionId) {
        return sections.getById(sectionId)
                .filter(s -> s.getBlueprintId() == instance.getBlueprintId())
                .orElseThrow(() -> new NoSuchElementException("Section " + sectionId + " not found."));
    }

    private DocContentBlock requireBlock(int blockId) {
        DocContentBlock block = blocks.getById(blockId)
                .orElseThrow(() -> new NoSuchElementException("Block " + blockId + " not found."));
        try {
            ownership.requireInstance(block.getInstanceId());
        } catch (NoSuchElementException e) {
            throw new NoSuchElementException("Block " + blockId + " not found.");
        }
        return block;
    }

    private DocImageAsset requireAsset(int assetId) {
        DocImageAsset asset = assets.getById(assetId)
                .orElseThrow(() -> new NoSuchElementException("Image " + assetId + " not found."));
        try {
            ownership.requireInstance(asset.getInstanceId());
        } catch (NoSuchElementException e) {
            throw new NoSuchElementException("Image " + assetId + " not found.");
        }
        return asset;
    }

    private DocImageAsset requireAssetOf(int instanceId, int assetId) {
        DocImageAsset asset = requireAsset(assetId);
        if (asset.getInstanceId() != instanceId) {
            throw new NoSuchElementException("Image " + assetId + " not found.");
        }
        return asset;
    }

    private int nextOrderIndex(int instanceId, int sectionId) {
        return blocks.listBySection(instanceId, sectionId).stream()
                .mapToInt(DocContentBlock::getOrderIndex)
                .max()
                .orElse(-1) + 1;
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A document title is required.");
        }
        if (title.strip().length() > 255) {
            throw new IllegalArgumentException("The title is too long (255 characters max).");
        }
    }

    private static String requireCaption(String caption) {
        if (caption != null && caption.length() > 500) {
            throw new IllegalArgumentException("The caption is too long (500 characters max).");
        }
        return caption;
    }

    private static void touch(DocInstance instance) {
        instance.setUpdatedDate(LocalDateTime.now());
    }

    static String contentTypeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return IMAGE_CONTENT_TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The image type according to its bytes, or null when unreadable/unsupported. */
    static String sniffImageContentType(byte[] bytes) throws IOException {
        try (javax.imageio.stream.ImageInputStream in =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            String contentType = IMAGE_CONTENT_TYPES.get("jpeg".equals(format) ? "jpg" : format);
            // Confirm it actually decodes, not just that a header matched.
            return contentType != null && ImageIO.read(new ByteArrayInputStream(bytes)) != null
                    ? contentType : null;
        }
    }

    static String exportName(int instanceId, String extension) {
        return "doccreation-instance" + instanceId + "." + extension;
    }

    static String sanitizeFileName(String title) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").strip();
        return cleaned.isBlank() ? "document" : cleaned;
    }

    private static void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", path, e);
        }
    }

    public record StoredAsset(Path path, String contentType, String originalFileName) {
    }

    public record DownloadFile(Path path, String downloadName) {
    }
}
