package com.modocument.doccreation.usecase;

import com.modocument.testsupport.FixedUser;
import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.BlockDto;
import com.modocument.doccreation.dto.BulkUploadResultDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.dto.InstanceBuilderDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.application.port.AiFilesService;
import com.modocument.doccreation.flow.DefaultDocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.flow.KeywordSectionMatcher;
import com.modocument.doccreation.generate.AiDocumentComposer;
import com.modocument.doccreation.generate.DocumentAssembler;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import com.modocument.doccreation.pdf.PdfConverter;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocContentBlockRepository;
import com.modocument.doccreation.repo.DocImageAssetRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the asset&harr;block invariant and the end-to-end generate/download
 * path against in-memory repository fakes and a real blueprint .docx.
 */
class DocInstanceUseCaseTest {

    private static final int USER = 1;

    @TempDir
    Path tempDir;

    private final InMemoryBlueprints blueprints = new InMemoryBlueprints();
    private final InMemorySections sections = new InMemorySections();
    private final InMemoryInstances instances = new InMemoryInstances();
    private final InMemoryBlocks blocks = new InMemoryBlocks();
    private final InMemoryAssets assets = new InMemoryAssets();
    private final FakePdfConverter pdf = new FakePdfConverter();

    private DocInstanceUseCase useCase;
    private StorageProperties storage;
    private int blueprintId;
    private int roofSectionId;
    private int inverterSectionId;
    private int instanceId;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageProperties(tempDir.toString());
        UploadStorage uploadStorage = new UploadStorage(storage);
        // No doc:release in these tests — they cover the owner paths; the
        // ws11 review extension is exercised in DocReleaseSodIntegrationTest.
        DocCreationOwnership ownership = new DocCreationOwnership(blueprints, instances,
                FixedUser.provider(USER),
                org.mockito.Mockito.mock(com.modocument.api.security.PermissionChecker.class));
        DocumentCreationFlowRegistry flows = new DocumentCreationFlowRegistry(
                List.of(new DefaultDocumentCreationFlow(new KeywordSectionMatcher())));
        DocumentAssembler assembler = new DocumentAssembler();
        // AI composition disabled here: these tests cover the deterministic
        // engine; the AI path has its own suite (AiDocumentComposerTest).
        AiDocumentComposer disabledComposer = new AiDocumentComposer(
                org.mockito.Mockito.mock(AiFilesService.class),
                new LlmRateLimiter(1000, 3_600_000, 100), () -> USER, assembler,
                new com.modocument.application.privacy.PiiTokenizer(
                        new com.modocument.application.privacy.PrivacyProperties()), false, "");
        // Real masking stack (REPORT masks PII shapes; these fixtures carry
        // none, so the assembled text is byte-identical to the user's).
        com.modocument.application.privacy.PiiMaskingService masking =
                new com.modocument.application.privacy.PiiMaskingService(
                        new com.modocument.application.privacy.MaskingPolicy(
                                new com.modocument.application.privacy.MaskingProperties()),
                        new com.modocument.application.privacy.PiiClassifier(),
                        org.mockito.Mockito.mock(
                                com.modocument.application.port.TemplateFieldsRepository.class),
                        org.mockito.Mockito.mock(
                                com.modocument.application.port.ValidationRuleRepository.class),
                        FixedUser.provider(USER));
        useCase = new DocInstanceUseCase(instances, blueprints, sections, blocks, assets,
                ownership, flows, assembler, disabledComposer, pdf, uploadStorage, storage, masking);

        // A real blueprint file, so generate() runs the real assembler.
        Files.createDirectories(storage.uploadsDir());
        Path blueprintFile = storage.uploadsDir().resolve("blueprint.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Letterhead.");   // 0
            XWPFParagraph h1 = doc.createParagraph();                   // 1
            h1.setStyle("Heading1");
            h1.createRun().setText("Roof");
            XWPFParagraph h2 = doc.createParagraph();                   // 2
            h2.setStyle("Heading1");
            h2.createRun().setText("Inverter");
            try (OutputStream out = Files.newOutputStream(blueprintFile)) {
                doc.write(out);
            }
        }

        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(USER);
        blueprint.setName("PV Report");
        blueprint.setFileName("blueprint.docx");
        blueprint.setFlowKey("DEFAULT");
        blueprintId = blueprints.save(blueprint).getDocBlueprintId();

        roofSectionId = saveSection(0, "Roof", 1, "roof");
        inverterSectionId = saveSection(1, "Inverter", 2, "inverter,dc2");

        instanceId = useCase.create(blueprintId, "Survey 42").instanceId();
    }

    private int saveSection(int order, String title, int anchor, String keywords) {
        DocBlueprintSection section = new DocBlueprintSection();
        section.setBlueprintId(blueprintId);
        section.setOrderIndex(order);
        section.setTitle(title);
        section.setHeadingLevel(1);
        section.setAnchorParagraphIndex(anchor);
        section.setAnchorText(title);
        section.setKeywords(keywords);
        return sections.save(section).getDocBlueprintSectionId();
    }

    private static MockMultipartFile pngUpload(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("files", name, "image/png", out.toByteArray());
    }

    @Test
    void bulkUploadAutoAssignsByKeywordsAndKeepsTheRestUnsorted() throws IOException {
        BulkUploadResultDto result = useCase.uploadAssets(instanceId, List.of(
                pngUpload("roof_east.png"),
                pngUpload("dc2_cable.png"),
                pngUpload("IMG_1234.png"),
                new MockMultipartFile("files", "notes.txt", "text/plain", "hi".getBytes()),
                new MockMultipartFile("files", "broken.png", "image/png", "not an image".getBytes())),
                true);

        assertThat(result.assigned()).hasSize(2);
        assertThat(result.assigned()).anyMatch(a -> a.sectionId() == roofSectionId);
        assertThat(result.assigned()).anyMatch(a -> a.sectionId() == inverterSectionId);
        assertThat(result.unsorted()).singleElement()
                .satisfies(u -> assertThat(u.originalFileName()).isEqualTo("IMG_1234.png"));
        assertThat(result.rejected()).hasSize(2);

        // Invariant: each assigned asset has exactly one IMAGE block in its section.
        for (BulkUploadResultDto.AssignedDto assigned : result.assigned()) {
            DocContentBlock block = blocks.findByAsset(assigned.assetId()).orElseThrow();
            assertThat(block.getSectionId()).isEqualTo(assigned.sectionId());
            assertThat(assets.getById(assigned.assetId()).orElseThrow().getAssignedSectionId())
                    .isEqualTo(assigned.sectionId());
        }
    }

    @Test
    void anUnsortedAssetCanBePlacedMovedAndReturnedToUnsorted() throws IOException {
        BulkUploadResultDto uploaded =
                useCase.uploadAssets(instanceId, List.of(pngUpload("IMG_1.png")), true);
        int assetId = uploaded.unsorted().get(0).assetId();

        useCase.assignAsset(assetId, roofSectionId);
        assertThat(assets.getById(assetId).orElseThrow().getAssignedSectionId()).isEqualTo(roofSectionId);
        assertThat(blocks.findByAsset(assetId).orElseThrow().getSectionId()).isEqualTo(roofSectionId);

        useCase.assignAsset(assetId, inverterSectionId);
        assertThat(blocks.findByAsset(assetId).orElseThrow().getSectionId()).isEqualTo(inverterSectionId);

        useCase.assignAsset(assetId, null);
        assertThat(assets.getById(assetId).orElseThrow().getAssignedSectionId()).isNull();
        assertThat(blocks.findByAsset(assetId)).isEmpty();
    }

    @Test
    void deletingAnImageBlockReturnsTheAssetToUnsorted() throws IOException {
        BulkUploadResultDto uploaded =
                useCase.uploadAssets(instanceId, List.of(pngUpload("roof_1.png")), true);
        BulkUploadResultDto.AssignedDto assigned = uploaded.assigned().get(0);

        useCase.deleteBlock(assigned.blockId());

        assertThat(blocks.findByAsset(assigned.assetId())).isEmpty();
        assertThat(assets.getById(assigned.assetId()).orElseThrow().getAssignedSectionId()).isNull();
    }

    @Test
    void anAlreadyPlacedAssetCannotBackAnotherImageBlock() throws IOException {
        BulkUploadResultDto uploaded =
                useCase.uploadAssets(instanceId, List.of(pngUpload("roof_1.png")), true);
        AddBlockRequest again = new AddBlockRequest();
        again.setBlockType("IMAGE");
        again.setAssetId(uploaded.assigned().get(0).assetId());

        assertThatThrownBy(() -> useCase.addBlock(instanceId, inverterSectionId, again))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already placed");
    }

    @Test
    void reorderRejectsAnythingButAPermutation() {
        AddBlockRequest p1 = new AddBlockRequest();
        p1.setBlockType("PARAGRAPH");
        p1.setText("one");
        AddBlockRequest p2 = new AddBlockRequest();
        p2.setBlockType("PARAGRAPH");
        p2.setText("two");
        BlockDto b1 = useCase.addBlock(instanceId, roofSectionId, p1);
        BlockDto b2 = useCase.addBlock(instanceId, roofSectionId, p2);

        assertThatThrownBy(() -> useCase.reorderBlocks(instanceId, roofSectionId, List.of(b1.blockId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.reorderBlocks(instanceId, roofSectionId,
                List.of(b1.blockId(), 999)))
                .isInstanceOf(IllegalArgumentException.class);

        useCase.reorderBlocks(instanceId, roofSectionId, List.of(b2.blockId(), b1.blockId()));
        InstanceBuilderDto builder = useCase.getBuilder(instanceId);
        List<BlockDto> roofBlocks = builder.sections().stream()
                .filter(s -> s.sectionId() == roofSectionId)
                .findFirst().orElseThrow().blocks();
        assertThat(roofBlocks).extracting(BlockDto::blockId)
                .containsExactly(b2.blockId(), b1.blockId());
    }

    @Test
    void generateWritesTheDocxAndReportsPdfUnavailable() {
        AddBlockRequest paragraph = new AddBlockRequest();
        paragraph.setBlockType("PARAGRAPH");
        paragraph.setText("A roof note.");
        useCase.addBlock(instanceId, roofSectionId, paragraph);

        GenerationResultDto result = useCase.generate(instanceId);

        assertThat(result.docxFileName()).isEqualTo("doccreation-instance" + instanceId + ".docx");
        assertThat(Files.exists(storage.exportsDir().resolve(result.docxFileName()))).isTrue();
        assertThat(result.pdfReady()).isFalse();
        assertThat(result.pdfError()).contains("not available");

        DocInstanceUseCase.DownloadFile download = useCase.download(instanceId, "docx");
        assertThat(download.downloadName()).isEqualTo("Survey 42.docx");

        assertThatThrownBy(() -> useCase.download(instanceId, "pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void downloadRegeneratesWhenContentChangedSinceTheLastGeneration() {
        useCase.generate(instanceId);
        AddBlockRequest paragraph = new AddBlockRequest();
        paragraph.setBlockType("PARAGRAPH");
        paragraph.setText("Added later.");
        useCase.addBlock(instanceId, roofSectionId, paragraph);

        useCase.download(instanceId, "docx");

        DocInstance instance = instances.getByIdAndOwner(instanceId, USER).orElseThrow();
        assertThat(instance.getLastGeneratedDate()).isAfterOrEqualTo(instance.getUpdatedDate());
    }

    @Test
    void foreignResourcesAnswerNotFound() {
        DocInstance foreign = new DocInstance();
        foreign.setOwnerUserId(999);
        foreign.setBlueprintId(blueprintId);
        foreign.setTitle("someone else's");
        int foreignId = instances.save(foreign).getDocInstanceId();

        assertThatThrownBy(() -> useCase.getBuilder(foreignId))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --------------------------------------------------------------- fakes

    private static final class FakePdfConverter implements PdfConverter {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public byte[] convert(Path docxFile) {
            throw new IllegalStateException("PDF conversion is not available on this server.");
        }
    }

    private static final class InMemoryBlueprints implements DocBlueprintRepository {
        private final Map<Integer, DocBlueprint> rows = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<DocBlueprint> getByIdAndOwner(int id, int ownerUserId) {
            return Optional.ofNullable(rows.get(id)).filter(b -> b.getOwnerUserId() == ownerUserId);
        }

        @Override
        public Optional<DocBlueprint> getById(int id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<DocBlueprint> listByOwner(int ownerUserId) {
            return rows.values().stream().filter(b -> b.getOwnerUserId() == ownerUserId).toList();
        }

        @Override
        public DocBlueprint save(DocBlueprint entity) {
            if (entity.getDocBlueprintId() == 0) {
                entity.setDocBlueprintId(nextId++);
            }
            rows.put(entity.getDocBlueprintId(), entity);
            return entity;
        }

        @Override
        public void delete(int id) {
            rows.remove(id);
        }
    }

    private static final class InMemorySections implements DocBlueprintSectionRepository {
        private final Map<Integer, DocBlueprintSection> rows = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<DocBlueprintSection> getById(int id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<DocBlueprintSection> listByBlueprint(int blueprintId) {
            return rows.values().stream()
                    .filter(s -> s.getBlueprintId() == blueprintId)
                    .sorted(Comparator.comparingInt(DocBlueprintSection::getOrderIndex))
                    .toList();
        }

        @Override
        public DocBlueprintSection save(DocBlueprintSection entity) {
            if (entity.getDocBlueprintSectionId() == 0) {
                entity.setDocBlueprintSectionId(nextId++);
            }
            rows.put(entity.getDocBlueprintSectionId(), entity);
            return entity;
        }

        @Override
        public void deleteByBlueprint(int blueprintId) {
            rows.values().removeIf(s -> s.getBlueprintId() == blueprintId);
        }
    }

    private static final class InMemoryInstances implements DocInstanceRepository {
        private final Map<Integer, DocInstance> rows = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<DocInstance> getByIdAndOwner(int id, int ownerUserId) {
            return Optional.ofNullable(rows.get(id)).filter(i -> i.getOwnerUserId() == ownerUserId);
        }

        @Override
        public Optional<DocInstance> getByIdAndBank(int id, int bankId) {
            return Optional.ofNullable(rows.get(id)).filter(i -> i.getBankId() == bankId);
        }

        @Override
        public List<DocInstance> listByBankAndStatus(int bankId, String status) {
            return rows.values().stream()
                    .filter(i -> i.getBankId() == bankId && status.equals(i.getStatus()))
                    .toList();
        }

        @Override
        public List<DocInstance> listReleasedSodViolations(int bankId) {
            return rows.values().stream()
                    .filter(i -> i.getBankId() == bankId
                            && DocInstance.STATUS_RELEASED.equals(i.getStatus())
                            && i.getReleasedByUserId() != null
                            && (i.getReleasedByUserId() == i.getOwnerUserId()
                                    || i.getReleasedByUserId().equals(i.getSubmittedByUserId())))
                    .toList();
        }

        @Override
        public List<DocInstance> listByOwner(int ownerUserId) {
            return rows.values().stream().filter(i -> i.getOwnerUserId() == ownerUserId).toList();
        }

        @Override
        public List<DocInstance> listByOwnerAndBlueprint(int ownerUserId, int blueprintId) {
            return listByOwner(ownerUserId).stream()
                    .filter(i -> i.getBlueprintId() == blueprintId).toList();
        }

        @Override
        public int countByBlueprint(int blueprintId) {
            return (int) rows.values().stream().filter(i -> i.getBlueprintId() == blueprintId).count();
        }

        @Override
        public DocInstance save(DocInstance entity) {
            if (entity.getDocInstanceId() == 0) {
                entity.setDocInstanceId(nextId++);
            }
            rows.put(entity.getDocInstanceId(), entity);
            return entity;
        }

        @Override
        public void delete(int id) {
            rows.remove(id);
        }
    }

    private static final class InMemoryAssets implements DocImageAssetRepository {
        private final Map<Integer, DocImageAsset> rows = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<DocImageAsset> getById(int id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<DocImageAsset> listByInstance(int instanceId) {
            return rows.values().stream().filter(a -> a.getInstanceId() == instanceId)
                    .sorted(Comparator.comparingInt(DocImageAsset::getDocImageAssetId)).toList();
        }

        @Override
        public DocImageAsset save(DocImageAsset entity) {
            if (entity.getDocImageAssetId() == 0) {
                entity.setDocImageAssetId(nextId++);
            }
            rows.put(entity.getDocImageAssetId(), entity);
            return entity;
        }

        @Override
        public void delete(int id) {
            rows.remove(id);
        }

        @Override
        public void deleteByInstance(int instanceId) {
            rows.values().removeIf(a -> a.getInstanceId() == instanceId);
        }
    }

    private static final class InMemoryBlocks implements DocContentBlockRepository {
        private final Map<Integer, DocContentBlock> rows = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<DocContentBlock> getById(int id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<DocContentBlock> listByInstance(int instanceId) {
            return rows.values().stream().filter(b -> b.getInstanceId() == instanceId)
                    .sorted(Comparator.comparingInt(DocContentBlock::getSectionId)
                            .thenComparingInt(DocContentBlock::getOrderIndex))
                    .toList();
        }

        @Override
        public List<DocContentBlock> listBySection(int instanceId, int sectionId) {
            return listByInstance(instanceId).stream()
                    .filter(b -> b.getSectionId() == sectionId)
                    .sorted(Comparator.comparingInt(DocContentBlock::getOrderIndex))
                    .toList();
        }

        @Override
        public Optional<DocContentBlock> findByAsset(int assetId) {
            return rows.values().stream()
                    .filter(b -> b.getAssetId() != null && b.getAssetId() == assetId)
                    .findFirst();
        }

        @Override
        public DocContentBlock save(DocContentBlock entity) {
            if (entity.getDocContentBlockId() == 0) {
                entity.setDocContentBlockId(nextId++);
            }
            rows.put(entity.getDocContentBlockId(), entity);
            return entity;
        }

        @Override
        public void delete(int id) {
            rows.remove(id);
        }

        @Override
        public void deleteByInstance(int instanceId) {
            rows.values().removeIf(b -> b.getInstanceId() == instanceId);
        }
    }
}
