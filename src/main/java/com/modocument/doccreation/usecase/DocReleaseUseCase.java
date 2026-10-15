package com.modocument.doccreation.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.security.SodPolicy;
import com.modocument.doccreation.dto.BlockDto;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.PendingReleaseDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocContentBlockRepository;
import com.modocument.doccreation.repo.DocImageAssetRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ws11 release workflow on a document instance (gate B8, SEC-012,
 * SEC-024 runtime layer; C10 human review of AI-written content).
 *
 * <p>The owner SUBMITS their generated document
 * (GENERATED &rarr; SUBMITTED); a user holding {@code doc:release} RELEASES
 * it (SUBMITTED &rarr; RELEASED) or REJECTS it back to GENERATED.
 * <b>The B8 invariant:</b> the releaser must differ from BOTH the author
 * (owner) and the submitter — enforced through the shared
 * {@link SodPolicy#verifyDistinct} (the same mechanism as the ws10
 * maker/checker gate), a hard 409 with a DOC_SELF_RELEASE_BLOCKED (DENIED)
 * audit row written in its own transaction, so the evidence survives the
 * refusal — even when the author also holds the Approver role.
 *
 * <p>C10: the pending-release queue hands the approver the full content
 * blocks plus the {@code aiComposed} flag, so releasing an AI-composed
 * document is a documented human check of the model's text and figures; the
 * approver's comment is recorded on the row and the DOC_RELEASED audit
 * event carries the flag and the comment.
 *
 * <p>Bank scoping: approvers review their own bank only — the repository
 * filters by the caller's bank (H2 tests + defense in depth) and the RLS
 * policies enforce the same on PostgreSQL.
 */
@Service
public class DocReleaseUseCase {

    private final DocInstanceRepository instances;
    private final DocBlueprintRepository blueprints;
    private final DocBlueprintSectionRepository sections;
    private final DocContentBlockRepository blocks;
    private final DocImageAssetRepository assets;
    private final DocCreationOwnership ownership;
    private final AppUserRepository users;
    private final AuditRecorder audit;
    private final SodPolicy sodPolicy;
    private final FileStore storage;
    /** ws25: DOC_RELEASED joins the release transaction (outbound EVENT flows). */
    private final com.modocument.application.port.integration.IntegrationEventLog eventLog;

    public DocReleaseUseCase(DocInstanceRepository instances,
                             DocBlueprintRepository blueprints,
                             DocBlueprintSectionRepository sections,
                             DocContentBlockRepository blocks,
                             DocImageAssetRepository assets,
                             DocCreationOwnership ownership,
                             AppUserRepository users,
                             AuditRecorder audit,
                             SodPolicy sodPolicy,
                             FileStore storage,
                             com.modocument.application.port.integration.IntegrationEventLog eventLog) {
        this.eventLog = eventLog;
        this.instances = instances;
        this.blueprints = blueprints;
        this.sections = sections;
        this.blocks = blocks;
        this.assets = assets;
        this.ownership = ownership;
        this.users = users;
        this.audit = audit;
        this.sodPolicy = sodPolicy;
        this.storage = storage;
    }

    // ------------------------------------------------------------ author side

    /**
     * Owner side: sends the generated document for release review
     * (GENERATED &rarr; SUBMITTED). Only the instance's owner can (the
     * lookup is owner-scoped), and only a generated one — there is nothing
     * to review before the first generation.
     */
    @Transactional
    public InstanceSummaryDto submit(int instanceId) {
        DocInstance instance = ownership.requireInstance(instanceId);
        switch (instance.getStatus()) {
            case DocInstance.STATUS_GENERATED -> { /* the one submittable state */ }
            case DocInstance.STATUS_DRAFT -> throw new IllegalStateException(
                    "Generate the document before submitting it for release.");
            case DocInstance.STATUS_SUBMITTED -> throw new IllegalStateException(
                    "This document is already submitted for release.");
            default -> throw new IllegalStateException(
                    "This document is already released.");
        }
        instance.setStatus(DocInstance.STATUS_SUBMITTED);
        instance.setSubmittedByUserId(ownership.requireUserId());
        instance.setSubmittedAt(LocalDateTime.now());
        // A fresh review cycle: an earlier rejection's comment is spent.
        instance.setReviewComment(null);
        DocInstance saved = instances.save(instance);

        audit.record(AuditEventSpec.builder("DOC_SUBMITTED", AuditOutcome.SUCCESS)
                .entity("DocInstance", String.valueOf(saved.getDocInstanceId()))
                .newValues(summary(saved))
                .build());
        DocBlueprint blueprint = ownership.requireBlueprint(saved.getBlueprintId());
        return InstanceSummaryDto.from(saved, blueprint.getName());
    }

    // ---------------------------------------------------------- approver side

    /**
     * The bank's release queue (SUBMITTED instances, newest first) with the
     * full content per section — the approver reviews the actual text and
     * figures (C10), not just a title.
     */
    @Transactional(readOnly = true)
    public List<PendingReleaseDto> pendingReleases() {
        int bankId = ownership.requireBankId();
        List<DocInstance> queue = instances.listByBankAndStatus(bankId, DocInstance.STATUS_SUBMITTED);
        if (queue.isEmpty()) {
            return List.of();
        }
        // ws28: the queue's blueprints, sections, blocks, assets and users are
        // loaded in one statement each, not once per queued document.
        Set<Integer> instanceIds = new LinkedHashSet<>();
        Set<Integer> blueprintIds = new LinkedHashSet<>();
        Set<Integer> userIds = new LinkedHashSet<>();
        for (DocInstance instance : queue) {
            instanceIds.add(instance.getDocInstanceId());
            blueprintIds.add(instance.getBlueprintId());
            userIds.add(instance.getOwnerUserId());
            if (instance.getSubmittedByUserId() != null) {
                userIds.add(instance.getSubmittedByUserId());
            }
        }
        ReleaseQueueContext context = new ReleaseQueueContext(
                blueprints.getByIds(blueprintIds),
                sections.listByBlueprints(blueprintIds),
                blocks.listByInstances(instanceIds),
                assets.listByInstances(instanceIds),
                emails(userIds));
        return queue.stream()
                .map(instance -> toPendingRelease(instance, context))
                .toList();
    }

    /** Everything the release queue needs, batch-loaded once for the whole queue. */
    private record ReleaseQueueContext(Map<Integer, DocBlueprint> blueprints,
                                       Map<Integer, List<DocBlueprintSection>> sections,
                                       Map<Integer, List<DocContentBlock>> blocks,
                                       Map<Integer, List<DocImageAsset>> assets,
                                       Map<Integer, String> emails) {
    }

    private Map<Integer, String> emails(Set<Integer> userIds) {
        Map<Integer, String> emails = new java.util.HashMap<>();
        users.getByIds(userIds).forEach((id, user) -> emails.put(id, user.getEmail()));
        return emails;
    }

    /**
     * Checker side, the B8 gate: a DIFFERENT user releases
     * (SUBMITTED &rarr; RELEASED). The author (owner) or submitter releasing
     * their own document is a hard 409, audited DOC_SELF_RELEASE_BLOCKED
     * (DENIED) — regardless of the roles they hold.
     */
    @Transactional
    public InstanceSummaryDto release(int instanceId, String comment) {
        DocInstance instance = ownership.requireBankInstanceForRelease(instanceId);
        requireSubmitted(instance);
        int releaserId = ownership.requireUserId();
        verifyReleaserIsNotAParty(instance, releaserId);

        LocalDateTime now = LocalDateTime.now();
        instance.setStatus(DocInstance.STATUS_RELEASED);
        instance.setReleasedByUserId(releaserId);
        instance.setReleasedAt(now);
        instance.setReviewComment(clean(comment));
        DocInstance saved = instances.save(instance);

        // C10 evidence: the release event names the human and carries the
        // aiComposed flag and their review comment.
        Map<String, Object> values = summary(saved);
        values.put("reviewComment", String.valueOf(saved.getReviewComment()));
        audit.record(AuditEventSpec.builder("DOC_RELEASED", AuditOutcome.SUCCESS)
                .entity("DocInstance", String.valueOf(saved.getDocInstanceId()))
                .newValues(values)
                .build());
        // ws25: the integration event (and the outbox rows of subscribed
        // outbound flows) commit with the release, or not at all.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("documentId", saved.getDocInstanceId());
        event.put("blueprintId", saved.getBlueprintId());
        event.put("aiComposed", saved.isAiComposed());
        eventLog.publish(saved.getBankId(), com.modocument.domain.model.integration.FlowEvent.DOC_RELEASED,
                "DocInstance", String.valueOf(saved.getDocInstanceId()), event);
        return toSummary(saved);
    }

    /**
     * Checker side: refuses the release — the instance returns to GENERATED
     * (a rejection is a transition, not a stored status) for the author to
     * rework and resubmit; the reason lives in ReviewComment and the
     * DOC_RELEASE_REJECTED audit row. The same B8 check applies: a party to
     * the document cannot decide on it, not even to reject.
     */
    @Transactional
    public InstanceSummaryDto reject(int instanceId, String comment) {
        DocInstance instance = ownership.requireBankInstanceForRelease(instanceId);
        requireSubmitted(instance);
        verifyReleaserIsNotAParty(instance, ownership.requireUserId());

        instance.setStatus(DocInstance.STATUS_GENERATED);
        instance.setReviewComment(clean(comment));
        DocInstance saved = instances.save(instance);

        Map<String, Object> values = summary(saved);
        values.put("reviewComment", String.valueOf(saved.getReviewComment()));
        audit.record(AuditEventSpec.builder("DOC_RELEASE_REJECTED", AuditOutcome.SUCCESS)
                .entity("DocInstance", String.valueOf(saved.getDocInstanceId()))
                .newValues(values)
                .build());
        return toSummary(saved);
    }

    /**
     * Review download for {@code doc:release} holders: the SUBMITTED
     * document of the caller's bank, served EXACTLY as last generated —
     * deliberately no regeneration here (the owner's download regenerates
     * stale content; a reviewer must see what exists, not trigger new
     * LLM/conversion work on someone else's instance).
     */
    public DocInstanceUseCase.DownloadFile downloadForReview(int instanceId, String format) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"docx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("format must be docx or pdf.");
        }
        DocInstance instance = ownership.requireSubmittedForReview(instanceId);
        String fileName = pdf ? instance.getLastGeneratedPdf() : instance.getLastGeneratedDocx();
        if (fileName == null) {
            throw new IllegalStateException(pdf
                    ? "No PDF was generated for this document; review the .docx instead."
                    : "The document has no generated file; ask the author to generate it again.");
        }
        Path path;
        try {
            path = storage.localCopy(FileStoreKeys.export(fileName));
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalStateException(
                    "The generated file is missing; ask the author to generate it again.");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read the generated file.", e);
        }
        return new DocInstanceUseCase.DownloadFile(path,
                DocInstanceUseCase.sanitizeFileName(instance.getTitle()) + (pdf ? ".pdf" : ".docx"));
    }

    // -------------------------------------------------------------- plumbing

    /** The B8 runtime gate: releaser != author AND releaser != submitter. */
    private void verifyReleaserIsNotAParty(DocInstance instance, int releaserId) {
        try {
            sodPolicy.verifyDistinct(releaserId, instance.getOwnerUserId(),
                    SodPolicy.Operation.DOC_RELEASE);
            if (instance.getSubmittedByUserId() != null) {
                sodPolicy.verifyDistinct(releaserId, instance.getSubmittedByUserId(),
                        SodPolicy.Operation.DOC_RELEASE);
            }
        } catch (SodPolicy.SodViolationException blocked) {
            // DENIED writes in its own transaction (JpaAuditRecorder), so
            // this evidence row survives the 409 below.
            audit.record(AuditEventSpec.builder(blocked.operation().auditAction(),
                            AuditOutcome.DENIED)
                    .entity("DocInstance", String.valueOf(instance.getDocInstanceId()))
                    .newValues(summary(instance))
                    .build());
            throw blocked;
        }
    }

    private static void requireSubmitted(DocInstance instance) {
        if (!DocInstance.STATUS_SUBMITTED.equals(instance.getStatus())) {
            throw new IllegalStateException(
                    "This document is not awaiting release (status "
                            + instance.getStatus() + ").");
        }
    }

    private static PendingReleaseDto toPendingRelease(DocInstance instance, ReleaseQueueContext context) {
        Optional<DocBlueprint> blueprint =
                Optional.ofNullable(context.blueprints().get(instance.getBlueprintId()));
        Map<Integer, List<DocContentBlock>> blocksBySection =
                context.blocks().getOrDefault(instance.getDocInstanceId(), List.of()).stream()
                        .collect(Collectors.groupingBy(DocContentBlock::getSectionId));
        Map<Integer, DocImageAsset> assetsById =
                context.assets().getOrDefault(instance.getDocInstanceId(), List.of()).stream()
                        .collect(Collectors.toMap(DocImageAsset::getDocImageAssetId,
                                Function.identity()));
        List<PendingReleaseDto.ReviewSectionDto> sectionDtos = blueprint
                .map(b -> context.sections().getOrDefault(b.getDocBlueprintId(), List.of()))
                .orElse(List.of()).stream()
                .filter(s -> s.getVersion() == instance.getBlueprintVersion())
                .map(s -> toReviewSection(s, blocksBySection, assetsById))
                .toList();
        return new PendingReleaseDto(
                instance.getDocInstanceId(),
                instance.getTitle(),
                blueprint.map(DocBlueprint::getName).orElse(""),
                instance.getOwnerUserId(),
                context.emails().get(instance.getOwnerUserId()),
                instance.getSubmittedByUserId(),
                instance.getSubmittedByUserId() == null
                        ? null : context.emails().get(instance.getSubmittedByUserId()),
                instance.getSubmittedAt(),
                instance.getLastGeneratedDate(),
                instance.isAiComposed(),
                sectionDtos);
    }

    private static PendingReleaseDto.ReviewSectionDto toReviewSection(
            DocBlueprintSection section,
            Map<Integer, List<DocContentBlock>> blocksBySection,
            Map<Integer, DocImageAsset> assetsById) {
        List<BlockDto> blockDtos = blocksBySection
                .getOrDefault(section.getDocBlueprintSectionId(), List.of()).stream()
                .sorted(java.util.Comparator.comparingInt(DocContentBlock::getOrderIndex))
                .map(b -> BlockDto.from(b, b.getAssetId() == null ? null
                        : Optional.ofNullable(assetsById.get(b.getAssetId()))
                                .map(DocImageAsset::getOriginalFileName).orElse(null)))
                .toList();
        return new PendingReleaseDto.ReviewSectionDto(
                section.getDocBlueprintSectionId(), section.getTitle(), blockDtos);
    }

    private InstanceSummaryDto toSummary(DocInstance instance) {
        // Approver path: the blueprint belongs to the author, so the lookup
        // is by id (access was authorized through the bank-scoped instance).
        String blueprintName = blueprints.getById(instance.getBlueprintId())
                .map(DocBlueprint::getName).orElse("");
        return InstanceSummaryDto.from(instance, blueprintName);
    }

    /** The audited view: the parties and the aiComposed flag, never content. */
    private static Map<String, Object> summary(DocInstance instance) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ownerUserId", instance.getOwnerUserId());
        values.put("submittedByUserId", instance.getSubmittedByUserId());
        values.put("releasedByUserId", instance.getReleasedByUserId());
        values.put("status", instance.getStatus());
        values.put("aiComposed", instance.isAiComposed());
        return values;
    }

    private static String clean(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }
}
