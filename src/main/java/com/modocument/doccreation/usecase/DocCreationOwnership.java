package com.modocument.doccreation.usecase;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Shared authorization guard for document-creation resources. Sections belong
 * to whoever owns their blueprint; assets and content blocks to whoever owns
 * their instance — so every endpoint resolves ownership here, one place (the
 * doccreation counterpart of TemplateOwnership).
 */
@Service
public class DocCreationOwnership {

    private final DocBlueprintRepository blueprints;
    private final DocInstanceRepository instances;
    private final CurrentUserProvider currentUser;

    public DocCreationOwnership(DocBlueprintRepository blueprints,
                                DocInstanceRepository instances,
                                CurrentUserProvider currentUser) {
        this.blueprints = blueprints;
        this.instances = instances;
        this.currentUser = currentUser;
    }

    /**
     * @throws NoSuchElementException when the blueprint does not exist or is
     *     not the caller's — surfaced as 404 so cross-user probing can't tell
     *     the two apart.
     */
    public DocBlueprint requireBlueprint(int blueprintId) {
        return blueprints.getByIdAndOwner(blueprintId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Blueprint " + blueprintId + " not found."));
    }

    /** Same contract as {@link #requireBlueprint(int)}, for document instances. */
    public DocInstance requireInstance(int instanceId) {
        return instances.getByIdAndOwner(instanceId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Document " + instanceId + " not found."));
    }

    public int requireUserId() {
        return currentUser.requireUserId();
    }
}
