package com.modocument.doccreation;

import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-user isolation for the document-creation tables against the real
 * (H2-from-entities) schema — and, as a side effect, the canary that keeps the
 * doccreation entities in sync with V9__document_creation.sql.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocCreationOwnershipIntegrationTest {

    private static final int ALICE = 111;
    private static final int BOB = 222;

    @Autowired
    private DocBlueprintRepository blueprints;
    @Autowired
    private DocInstanceRepository instances;

    @Test
    void aBlueprintIsInvisibleToAnotherUser() {
        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(ALICE);
        blueprint.setName("Alice's report");
        blueprint.setFileName("alice.docx");
        blueprint.setFlowKey("DEFAULT");
        int id = blueprints.save(blueprint).getDocBlueprintId();

        assertThat(blueprints.getByIdAndOwner(id, ALICE)).isPresent();
        assertThat(blueprints.getByIdAndOwner(id, BOB)).isEmpty();
        assertThat(blueprints.listByOwner(BOB)).noneMatch(b -> b.getDocBlueprintId() == id);
    }

    @Test
    void aDocumentInstanceIsInvisibleToAnotherUser() {
        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(ALICE);
        instance.setBlueprintId(1);
        instance.setTitle("Alice's document");
        int id = instances.save(instance).getDocInstanceId();

        assertThat(instances.getByIdAndOwner(id, ALICE)).isPresent();
        assertThat(instances.getByIdAndOwner(id, BOB)).isEmpty();
        assertThat(instances.listByOwner(BOB)).noneMatch(i -> i.getDocInstanceId() == id);
    }
}
