package com.modocument.doccreation.usecase;

import com.modocument.api.security.PermissionChecker;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.model.Permission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Shared authorization guard for document-creation resources. Sections belong
 * to whoever owns their blueprint; assets and content blocks to whoever owns
 * their instance — so every endpoint resolves ownership here, one place (the
 * doccreation counterpart of TemplateOwnership).
 *
 * <p>The ONE non-owner read (ws11 release review):
 * {@link #requireSubmittedForReview(int)} lets a {@code doc:release} holder
 * reach a SUBMITTED instance of their own bank — approvers review other
 * users' work. Everything else stays strictly owner-scoped.
 */
@Service
public class DocCreationOwnership {

    private final DocBlueprintRepository blueprints;
    private final DocInstanceRepository instances;
    private final CurrentUserProvider currentUser;
    private final PermissionChecker perm;
    /** Null = no probe audit (unit-test wiring). */
    private final AuditRecorder audit;

    public DocCreationOwnership(DocBlueprintRepository blueprints,
                                DocInstanceRepository instances,
                                CurrentUserProvider currentUser,
                                PermissionChecker perm) {
        this(blueprints, instances, currentUser, perm, null);
    }

    @Autowired
    public DocCreationOwnership(DocBlueprintRepository blueprints,
                                DocInstanceRepository instances,
                                CurrentUserProvider currentUser,
                                PermissionChecker perm,
                                AuditRecorder audit) {
        this.blueprints = blueprints;
        this.instances = instances;
        this.currentUser = currentUser;
        this.perm = perm;
        this.audit = audit;
    }

    /** ws28: an owner-scoped 404 is audited ACCESS_DENIED (aggregated by the recorder). */
    private NoSuchElementException probe(String entityType, int id, String message) {
        if (audit != null) {
            audit.record(AuditActions.notFoundProbe(entityType, id));
        }
        return new NoSuchElementException(message);
    }

    /**
     * @throws NoSuchElementException when the blueprint does not exist or is
     *     not the caller's — surfaced as 404 so cross-user probing can't tell
     *     the two apart.
     */
    public DocBlueprint requireBlueprint(int blueprintId) {
        return blueprints.getByIdAndOwner(blueprintId, currentUser.requireUserId()).orElseThrow(
                () -> probe("DocBlueprint", blueprintId, "Blueprint " + blueprintId + " not found."));
    }

    /** Same contract as {@link #requireBlueprint(int)}, for document instances. */
    public DocInstance requireInstance(int instanceId) {
        return instances.getByIdAndOwner(instanceId, currentUser.requireUserId()).orElseThrow(
                () -> probe("DocInstance", instanceId, "Document " + instanceId + " not found."));
    }

    /**
     * The ws11 review extension of the ownership rule, for the release
     * workflow ONLY: a caller holding {@link Permission#DOC_RELEASE} may
     * reach a SUBMITTED instance of THEIR OWN BANK regardless of ownership
     * (the permission is re-checked here — defense in depth under the
     * route-level {@code @RequiresPermission}). Anything else — another
     * bank's instance, an unsubmitted one, a caller without the permission —
     * answers exactly like a missing id, so probing can't tell the cases
     * apart.
     */
    public DocInstance requireSubmittedForReview(int instanceId) {
        DocInstance instance = requireBankInstanceForRelease(instanceId);
        if (!DocInstance.STATUS_SUBMITTED.equals(instance.getStatus())) {
            throw new NoSuchElementException("Document " + instanceId + " not found.");
        }
        return instance;
    }

    /**
     * Bank-wide lookup for the ws11 release decisions (any status — the use
     * case turns a wrong status into a 409 so "already released" is
     * distinguishable from "not yours"). Same permission re-check and 404
     * contract as {@link #requireSubmittedForReview(int)}.
     */
    public DocInstance requireBankInstanceForRelease(int instanceId) {
        if (!perm.has(Permission.DOC_RELEASE.name())) {
            throw new NoSuchElementException("Document " + instanceId + " not found.");
        }
        return instances.getByIdAndBank(instanceId, currentUser.requireBankId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Document " + instanceId + " not found."));
    }

    public int requireUserId() {
        return currentUser.requireUserId();
    }

    /** The caller's bank, for stamping BankId on created rows. */
    public int requireBankId() {
        return currentUser.requireBankId();
    }
}
