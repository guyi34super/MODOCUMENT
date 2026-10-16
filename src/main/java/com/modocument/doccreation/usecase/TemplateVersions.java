package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintVersion;
import com.modocument.doccreation.entity.DocTemplateRegion;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.fields.FieldPropsCodec;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintVersionRepository;
import com.modocument.doccreation.repo.DocTemplateEditRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read access to a PDF template's versions, shared by the template, document
 * and release use cases: pages, the overlay of a version and its template
 * PDF. A template is a DRAFT while {@code CurrentVersion} is 0 (its proposed
 * version 1 is under review); version 1 is then the one shown.
 */
@Component
public class TemplateVersions {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_READY = "READY";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<PageInfo>> PAGES = new TypeReference<>() {
    };

    private final DocBlueprintVersionRepository versions;
    private final DocTemplateRegionRepository regions;
    private final DocTemplateEditRepository edits;
    private final PdfTemplateStore store;

    public TemplateVersions(DocBlueprintVersionRepository versions,
                            DocTemplateRegionRepository regions,
                            DocTemplateEditRepository edits,
                            PdfTemplateStore store) {
        this.versions = versions;
        this.regions = regions;
        this.edits = edits;
        this.store = store;
    }

    public static boolean isDraft(DocBlueprint blueprint) {
        return blueprint.getCurrentVersion() < 1;
    }

    public static String status(DocBlueprint blueprint) {
        return isDraft(blueprint) ? STATUS_DRAFT : STATUS_READY;
    }

    /** The version new documents (and the editor by default) use: 1 for a draft. */
    public static int effectiveVersion(DocBlueprint blueprint) {
        return Math.max(1, blueprint.getCurrentVersion());
    }

    /**
     * {@code requested} when it names an existing version, the effective
     * version when null.
     *
     * @throws NoSuchElementException for a version that does not exist
     */
    public static int resolveVersion(DocBlueprint blueprint, Integer requested) {
        int latest = effectiveVersion(blueprint);
        int shown = requested == null ? latest : requested;
        if (shown < 1 || shown > latest) {
            throw new NoSuchElementException("Version " + shown + " of this template does not exist.");
        }
        return shown;
    }

    public static List<PageInfo> pages(DocBlueprint blueprint) {
        if (blueprint.getPagesJson() == null || blueprint.getPagesJson().isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(blueprint.getPagesJson(), PAGES));
        } catch (IOException e) {
            throw new IllegalStateException("The template's page list is unreadable.", e);
        }
    }

    public static String pagesJson(List<PageInfo> pages) {
        try {
            return JSON.writeValueAsString(pages);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the template's page list.", e);
        }
    }

    public List<TemplateRegion> regions(DocBlueprint blueprint, int version) {
        return regions.listByBlueprintVersion(blueprint.getBankId(), blueprint.getDocBlueprintId(), version)
                .stream().map(OverlayMapper::toRegion).toList();
    }

    /**
     * ws33: the field properties of one version's regions, by region key
     * (regions without properties are absent).
     */
    public Map<String, FieldProps> fieldProps(DocBlueprint blueprint, int version) {
        Map<String, FieldProps> out = new LinkedHashMap<>();
        for (DocTemplateRegion row : regions.listByBlueprintVersion(blueprint.getBankId(),
                blueprint.getDocBlueprintId(), version)) {
            FieldProps props = FieldPropsCodec.read(row.getFieldPropsJson());
            if (!props.isEmpty()) {
                out.put(row.getRegionKey(), props);
            }
        }
        return out;
    }

    public List<TemplateEdit> edits(DocBlueprint blueprint, int version) {
        return edits.listByBlueprintVersion(blueprint.getBankId(), blueprint.getDocBlueprintId(), version)
                .stream().map(OverlayMapper::toEdit).toList();
    }

    public DocBlueprintVersion versionRow(DocBlueprint blueprint, int version) {
        return versions.get(blueprint.getDocBlueprintId(), version)
                .orElseThrow(() -> new NoSuchElementException(
                        "Version " + version + " of this template does not exist."));
    }

    /** The template PDF of one version. */
    public byte[] templatePdf(DocBlueprint blueprint, int version) {
        DocBlueprintVersion row = versionRow(blueprint, version);
        if (row.getTemplatePdfKey() == null) {
            throw new IllegalStateException("Version " + version + " of this template has no PDF.");
        }
        return store.read(row.getTemplatePdfKey());
    }

    /** The uploaded PDF, unchanged. */
    public byte[] originalPdf(DocBlueprint blueprint) {
        if (blueprint.getOriginalFileKey() == null) {
            throw new IllegalStateException("This template has no original PDF.");
        }
        return store.read(blueprint.getOriginalFileKey());
    }
}
