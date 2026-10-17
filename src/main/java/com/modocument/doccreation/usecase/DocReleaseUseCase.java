package com.modocument.doccreation.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.security.SodPolicy;
import com.modocument.doccreation.dto.BlockDto;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.PendingReleaseDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.entity.DocInstanceImage;
import com.modocument.doccreation.entity.DocTemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocInstanceImageRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p>C10: the pending-release queue hands the approver the document's
 * values and photos plus the {@code aiComposed} flag (always false since the
 * PDF templates: generation draws exactly the entered values); the
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
    private final DocTemplateRegionRepository regions;
    private final DocInstanceImageRepository images;
    private final DocCreationOwnership ownership;
    private final AppUserRepository users;
    private final AuditRecorder audit;
    private final SodPolicy sodPolicy;
    private final FileStore storage;
    /** ws25: DOC_RELEASED joins the release transaction (outbound EVENT flows). */
    private final com.modocument.application.port.integration.IntegrationEventLog eventLog;

    public DocReleaseUseCase(DocInstanceRepository instances,
                             DocBlueprintRepository blueprints,
                             DocTemplateRegionRepository regions,
                             DocInstanceImageRepository images,
                             DocCreationOwnership ownership,
                             AppUserRepository users,
                             AuditRecorder audit,
                             SodPolicy sodPolicy,
                             FileStore storage,
                             com.modocument.application.port.integration.IntegrationEventLog eventLog) {
        this.eventLog = eventLog;
        this.instances = instances;
        this.blueprints = blueprints;
        this.regions = regions;
        this.images = images;
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
                    "Save and validate the document, then generate it, before submitting it for release.");
            case DocInstance.STATUS_VALIDATED -> throw new IllegalStateException(
                    "Generate the document before submitting it for release.");
            case DocInstance.STATUS_SUBMITTED -> throw new IllegalStateException(
                    "This document is already submitted for release.");
            default -> throw new IllegalStateException(
                    "This document is already released.");
        }
        // The approver reviews the generated PDF: it must show the current values.
        if (instance.getUpdatedDate() != null && instance.getLastGeneratedDate() != null
                && instance.getUpdatedDate().isAfter(instance.getLastGeneratedDate())) {
            throw new IllegalStateException(
                    "The document changed since it was generated; generate it again before submitting.");
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
        return InstanceSummaryDto.from(saved, blueprint.getName(), pageCount(blueprint));
    }

    // ---------------------------------------------------------- approver side

    /**
     * The bank's release queue (SUBMITTED instances, newest first) with the
     * full content — the approver reviews the actual values and photos (C10),
     * not just a title. Documents of retired Word templates are not listed.
     */
    @Transactional(readOnly = true)
    public List<PendingReleaseDto> pendingReleases() {
        int bankId = ownership.requireBankId();
        List<DocInstance> queue = instances.listByBankAndStatus(bankId, DocInstance.STATUS_SUBMITTED);
        if (queue.isEmpty()) {
            return List.of();
        }
        // ws28: the queue's blueprints, regions, photos and users are loaded
        // in one statement each, not once per queued document.
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
        Map<Integer, DocBlueprint> blueprintsById = blueprints.getByIds(blueprintIds);
        ReleaseQueueContext context = new ReleaseQueueContext(
                blueprintsById,
                regions.listByBlueprints(bankId, blueprintIds),
                images.listByInstances(bankId, instanceIds),
                emails(userIds));
        return queue.stream()
                .filter(instance -> {
                    DocBlueprint blueprint = blueprintsById.get(instance.getBlueprintId());
                    return blueprint == null || !blueprint.isLegacy();
                })
                .map(instance -> toPendingRelease(instance, context))
                .toList();
    }

    /** Everything the release queue needs, batch-loaded once for the whole queue. */
    private record ReleaseQueueContext(Map<Integer, DocBlueprint> blueprints,
                                       Map<Integer, List<DocTemplateRegion>> regions,
                                       Map<Integer, List<DocInstanceImage>> images,
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
     * the document cannot decide on it, not even to reject. ws33: the
     * validation (ValidatedHash) is kept — a SUBMITTED document cannot change
     * — so the returned document can be downloaded and regenerated as is.
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
     * stale content; a reviewer must see what exists, not trigger new work on
     * someone else's instance). Output is PDF only.
     */
    public DocInstanceUseCase.PdfDownload downloadForReview(int instanceId) {
        DocInstance instance = ownership.requireSubmittedForReview(instanceId);
        String key = instance.getGeneratedPdfKey();
        if (key == null && instance.getLastGeneratedPdf() != null) {
            key = FileStoreKeys.tryExport(instance.getLastGeneratedPdf()).orElse(null);
        }
        if (key == null) {
            throw new IllegalStateException(
                    "The document has no generated PDF; ask the author to generate it again.");
        }
        try (java.io.InputStream in = storage.open(key)) {
            return new DocInstanceUseCase.PdfDownload(in.readAllBytes(),
                    PdfTemplateStore.downloadName(instance.getTitle(), ".pdf"));
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalStateException(
                    "The generated file is missing; ask the author to generate it again.");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read the generated file.", e);
        }
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

    /**
     * The approver's view of one document, in the queue's (unchanged) shape:
     * one review section per region group (in reading order), each filled
     * value a PARAGRAPH "Label: value", each placed photo an IMAGE.
     */
    private static PendingReleaseDto toPendingRelease(DocInstance instance, ReleaseQueueContext context) {
        Optional<DocBlueprint> blueprint =
                Optional.ofNullable(context.blueprints().get(instance.getBlueprintId()));
        Map<String, String> values = DocInstanceUseCase.readValues(instance);
        Map<String, DocInstanceImage> photos = new LinkedHashMap<>();
        for (DocInstanceImage image : context.images().getOrDefault(instance.getDocInstanceId(), List.of())) {
            if (image.getRegionKey() != null) {
                photos.put(image.getRegionKey(), image);
            }
        }
        List<DocTemplateRegion> regionRows = context.regions()
                .getOrDefault(instance.getBlueprintId(), List.of()).stream()
                .filter(r -> r.getVersion() == instance.getBlueprintVersion())
                .sorted(java.util.Comparator.comparingInt(DocTemplateRegion::getSortOrder))
                .toList();
        Map<String, List<BlockDto>> byGroup = new LinkedHashMap<>();
        int position = 0;
        for (DocTemplateRegion region : regionRows) {
            String group = region.getGroupName() == null || region.getGroupName().isBlank()
                    ? "Fields" : region.getGroupName();
            DocInstanceImage photo = photos.get(region.getRegionKey());
            String value = values.get(region.getRegionKey());
            List<BlockDto> blocks = byGroup.computeIfAbsent(group, g -> new ArrayList<>());
            if (photo != null) {
                position++;
                blocks.add(new BlockDto(position, BlockDto.TYPE_IMAGE, position, null, null, region.getLabel(),
                        photo.getOriginalName()));
            } else if (value != null && !value.isEmpty()) {
                position++;
                blocks.add(new BlockDto(position, BlockDto.TYPE_PARAGRAPH, position,
                        region.getLabel() + ": " + value, null, null, null));
            }
        }
        List<PendingReleaseDto.ReviewSectionDto> sectionDtos = new ArrayList<>();
        int sectionId = 0;
        for (Map.Entry<String, List<BlockDto>> group : byGroup.entrySet()) {
            sectionId++;
            sectionDtos.add(new PendingReleaseDto.ReviewSectionDto(sectionId, group.getKey(), group.getValue()));
        }
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

    private InstanceSummaryDto toSummary(DocInstance instance) {
        // Approver path: the blueprint belongs to the author, so the lookup
        // is by id (access was authorized through the bank-scoped instance).
        Optional<DocBlueprint> blueprint = blueprints.getById(instance.getBlueprintId());
        return InstanceSummaryDto.from(instance, blueprint.map(DocBlueprint::getName).orElse(""),
                blueprint.map(DocReleaseUseCase::pageCount).orElse(0),
                blueprint.map(DocBlueprint::getRulesRevision).orElse(null));
    }

    private static int pageCount(DocBlueprint blueprint) {
        return blueprint.getPageCount() == null ? 0 : blueprint.getPageCount();
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
