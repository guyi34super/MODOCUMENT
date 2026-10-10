package com.modocument.application.privacy;

import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the masking matrix (ws12, gate C2, PRIV-005) at the channels'
 * choke points: {@link MaskingPolicy} says WHETHER a channel masks for the
 * caller's role, template-field classification ({@code TemplateFields.PiiKind},
 * explicit or {@link PiiClassifier}-inferred-and-persisted) says WHICH
 * columns, and {@link Masker} says HOW each kind masks.
 *
 * <p>Callers: the ingestion export endpoints (EXPORT — file formats,
 * database export, and the per-owner archive copies that reuse the same
 * bytes), the ingestion job read endpoints (SCREEN_READONLY /
 * SCREEN_WORKING, picked by the caller's role) and doc-creation generation
 * (REPORT — free-text shape masking, there are no field names in a
 * document). The ws14 DSR export deliberately does NOT route through here:
 * disclosure to the data subject is complete by design (PRIV-016).
 */
@Service
public class PiiMaskingService {

    private static final Logger log = LoggerFactory.getLogger(PiiMaskingService.class);

    private final MaskingPolicy policy;
    private final PiiClassifier classifier;
    private final TemplateFieldsRepository templateFields;
    private final ValidationRuleRepository validationRules;
    private final CurrentUserProvider currentUser;

    public PiiMaskingService(MaskingPolicy policy,
                             PiiClassifier classifier,
                             TemplateFieldsRepository templateFields,
                             ValidationRuleRepository validationRules,
                             CurrentUserProvider currentUser) {
        this.policy = policy;
        this.classifier = classifier;
        this.templateFields = templateFields;
        this.validationRules = validationRules;
        this.currentUser = currentUser;
    }

    /**
     * EXPORT channel: masks the classified columns of a job's exportable
     * table. Returns the table untouched when the policy says raw (it never
     * does for EXPORT today). The file download, its archive copy and the
     * database export all pass through here, so every export artifact
     * carries the same masked values.
     */
    public TableData maskForExport(int templateId, TableData table) {
        if (!policy.mask(MaskingPolicy.Channel.EXPORT, callerRole())) {
            return table;
        }
        return maskTable(table, fieldKinds(templateId, table));
    }

    /**
     * Screen channels: a READ_ONLY caller gets SCREEN_READONLY (masked), any
     * writing role gets SCREEN_WORKING (raw unless force-masked). Mapped
     * rows and validation payloads mask by the template-field
     * classification; the parsed table (raw source columns, pre-mapping)
     * masks by header-and-shape inference, which is not persisted — source
     * columns are not template fields.
     */
    public IngestionJobDto maskJobView(IngestionJobDto view) {
        if (view == null) {
            return null;
        }
        MaskingPolicy.Channel channel = policy.screenChannel(callerRole());
        if (!policy.mask(channel, callerRole())) {
            return view;
        }
        Map<String, PiiKind> kinds = fieldKinds(view.templateId(), view.mappedRows());
        return new IngestionJobDto(
                view.ingestionJobId(),
                view.templateId(),
                view.fileName(),
                view.importHistoryId(),
                view.currentStep(),
                view.status(),
                view.wizardStep(),
                view.errorMessage(),
                maskParsedTable(view.parsedTable()),
                view.suggestedMapping(),
                view.mapping(),
                maskTable(view.mappedRows(), kinds),
                maskValidationResults(view.validationResults(), kinds),
                view.validationErrorCount(),
                view.unmappedFields(),
                view.reachableSteps(),
                view.exportInfo(),
                view.createdDate(),
                view.updatedDate());
    }

    /** Masks every view of a listing the same way as a single view. */
    public List<IngestionJobDto> maskJobViews(List<IngestionJobDto> views) {
        if (views == null) {
            return null;
        }
        return views.stream().map(this::maskJobView).toList();
    }

    /**
     * REPORT channel: one-way shape masking for the free text that lands in
     * a generated document (paragraphs, image captions) — there are no
     * field names to classify by, so {@link Masker#maskShapes} covers the
     * NIC / account / email value shapes. Returns the text untouched when
     * the policy says raw.
     */
    public String maskReportText(String text) {
        if (!policy.mask(MaskingPolicy.Channel.REPORT, callerRole())) {
            return text;
        }
        return Masker.maskShapes(text);
    }

    // ---- classification ----------------------------------------------------

    /**
     * The template's field → kind map. An explicit tag (including NONE)
     * wins; an untagged field is classified once — field name, its
     * validation rules, sample values from the table at hand — and the
     * result is persisted so the owner sees and can correct it (PRIV-004).
     */
    public Map<String, PiiKind> fieldKinds(int templateId, TableData samples) {
        List<ValidationRule> rules = safeRules(templateId);
        Map<String, PiiKind> kinds = new LinkedHashMap<>();
        for (TemplateFields field : templateFields.listByTemplateId(templateId)) {
            PiiKind kind = field.getPiiKind();
            if (kind == null) {
                kind = classifier.infer(
                        field.getFieldName(),
                        rules.stream()
                                .filter(r -> field.getFieldName() != null
                                        && field.getFieldName().equals(r.getFieldName()))
                                .toList(),
                        sampleColumn(samples, field.getFieldName()));
                persistFirstClassification(field, kind);
            }
            kinds.put(field.getFieldName(), kind);
        }
        return kinds;
    }

    private void persistFirstClassification(TemplateFields field, PiiKind kind) {
        try {
            field.setPiiKind(kind);
            templateFields.update(field);
        } catch (RuntimeException e) {
            // Classification must never fail the read it rides on; the next
            // read simply classifies again.
            log.warn("Could not persist the PII classification of field '{}' (template {}): {}",
                    field.getFieldName(), field.getTemplateId(), e.getMessage());
        }
    }

    private List<ValidationRule> safeRules(int templateId) {
        try {
            return validationRules.getRulesByTemplate(templateId);
        } catch (RuntimeException e) {
            log.warn("Could not read validation rules of template {}: {}", templateId,
                    e.getMessage());
            return List.of();
        }
    }

    // ---- transforms ----------------------------------------------------------

    private static TableData maskTable(TableData table, Map<String, PiiKind> kinds) {
        if (table == null || table.isEmpty()) {
            return table;
        }
        List<Map<String, String>> maskedRows = new ArrayList<>(table.getRows().size());
        for (Map<String, String> row : table.getRows()) {
            Map<String, String> masked = new LinkedHashMap<>();
            for (Map.Entry<String, String> cell : row.entrySet()) {
                masked.put(cell.getKey(),
                        Masker.mask(kinds.get(cell.getKey()), cell.getValue()));
            }
            maskedRows.add(masked);
        }
        return new TableData(table.getColumns(), maskedRows);
    }

    /** Pre-mapping source columns: classified by header name + value shapes only. */
    private TableData maskParsedTable(TableData parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return parsed;
        }
        Map<String, PiiKind> kinds = new LinkedHashMap<>();
        for (String column : parsed.getColumns()) {
            kinds.put(column, classifier.infer(column, List.of(), sampleColumn(parsed, column)));
        }
        return maskTable(parsed, kinds);
    }

    private List<RowValidationResult> maskValidationResults(List<RowValidationResult> results,
                                                            Map<String, PiiKind> kinds) {
        if (results == null) {
            return null;
        }
        return results.stream()
                .map(result -> new RowValidationResult(
                        result.row(),
                        result.fieldName(),
                        Masker.mask(kinds.get(result.fieldName()), result.rawValue()),
                        // Rule messages can quote the failing value verbatim.
                        Masker.maskShapes(result.errorMessage()),
                        result.ruleType()))
                .toList();
    }

    private static List<String> sampleColumn(TableData table, String column) {
        if (table == null || table.getRows() == null || column == null) {
            return List.of();
        }
        return table.getRows().stream()
                .limit(PiiClassifier.SAMPLE_LIMIT)
                .map(row -> row.get(column))
                .toList();
    }

    /** Null when the token carries no roles claim — the policy fails closed. */
    private Role callerRole() {
        try {
            return currentUser.role();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
