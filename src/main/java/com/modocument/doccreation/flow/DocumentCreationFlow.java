package com.modocument.doccreation.flow;

import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.parse.ParsedSection;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.util.List;
import java.util.Optional;

/**
 * A company's document-creation flow. The engine (upload blueprint, break it
 * into sections, fill sections with paragraphs/images, regenerate the document
 * exactly) is shared; a flow customises the company-specific parts. Flows are
 * Spring beans looked up by {@link DocumentCreationFlowRegistry} from the
 * blueprint's companyId; there is no Company table yet, so companyId is a
 * free-form key stamped on the blueprint at upload — when a Company entity
 * arrives later, only {@link #appliesTo(String)} implementations and the
 * blueprint's column need to learn about it.
 *
 * <p>To give a company its own flow, add one {@code @Component} implementing
 * this interface: {@code flowKey()} = a short unique name, {@code appliesTo}
 * matching the company's id, and override whichever hooks differ from the
 * default behavior.
 */
public interface DocumentCreationFlow {

    String DEFAULT_FLOW_KEY = "DEFAULT";

    /** Short unique name stamped on blueprints handled by this flow. */
    String flowKey();

    /** Whether this flow handles blueprints of the given company id (never called with null). */
    boolean appliesTo(String companyId);

    /** Rework the parsed sections before they are stored (rename, merge, drop, reorder). */
    default List<ParsedSection> customizeSections(List<ParsedSection> parsed, DocBlueprint blueprint) {
        return parsed;
    }

    /**
     * Picks the section a bulk-uploaded image belongs to, or empty for the
     * Unsorted tray. The default flow matches section keywords against the
     * file name.
     */
    default Optional<Integer> assignSection(String originalFileName, List<DocBlueprintSection> sections) {
        return Optional.empty();
    }

    /** Last touch on the assembled document before it is written (stamps, footers, ...). */
    default void decorateGeneratedDocument(XWPFDocument doc, DocInstance instance, DocBlueprint blueprint) {
    }
}
