package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.PermissionChecker;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.doccreation.dto.RecordDto;
import com.modocument.doccreation.dto.RecordPageDto;
import com.modocument.doccreation.entity.TemplateRecord;
import com.modocument.doccreation.entity.TemplateRecordKey;
import com.modocument.doccreation.repo.TemplateRecordJpaRepository;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.TemplateVisibility;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The record index as the user sees it (docs/doccreation/flow-records-contract.md
 * §2/§4): search and read validated records, masked per role.
 *
 * <p>VISIBILITY — a record is visible when its template passes
 * {@link TemplateOwnership#requireReadable} (the caller's own template or
 * one shared with the bank) AND its job is the caller's own or the template
 * is shared with the bank ({@code Visibility=BANK}). Every query also names
 * the caller's bank (RLS enforces the same on PostgreSQL). An invisible
 * record answers exactly like a missing one (404).
 *
 * <p>Deliberately not {@code @Transactional}: the masking lookup may persist
 * a field's first PII classification, which a read-only transaction would
 * refuse; every repository call runs in its own transaction.
 */
@Service
public class TemplateRecordUseCase {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /** The most linked matches looked at (the newest wins, the rest are alternatives). */
    static final int MAX_LINKED_MATCHES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> VALUES = new TypeReference<>() {
    };

    private final TemplateRecordJpaRepository records;
    private final TemplateOwnership templateOwnership;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final CurrentUserProvider currentUser;
    private final PiiMaskingService masking;
    private final AuditRecorder audit;
    private final PermissionChecker perm;

    public TemplateRecordUseCase(TemplateRecordJpaRepository records,
                                 TemplateOwnership templateOwnership,
                                 TemplateRepository templates,
                                 TemplateFieldsRepository templateFields,
                                 CurrentUserProvider currentUser,
                                 PiiMaskingService masking,
                                 AuditRecorder audit,
                                 PermissionChecker perm) {
        this.records = records;
        this.templateOwnership = templateOwnership;
        this.templates = templates;
        this.templateFields = templateFields;
        this.currentUser = currentUser;
        this.masking = masking;
        this.audit = audit;
        this.perm = perm;
    }

    /** A visible record with its template, its raw values (server side only) and the caller's view of them. */
    public record VisibleRecord(TemplateRecord record, Template template, Map<String, String> raw,
                                Map<String, String> masked) {

        public long id() {
            return record.getTemplateRecordId();
        }

        public int templateId() {
            return record.getTemplateId();
        }
    }

    // --------------------------------------------------------------- search

    /**
     * {@code GET /api/doccreation/records}: one page of a template's visible
     * records, newest first. {@code q} matches every word in the search text
     * (case- and accent-insensitive contains); with {@code field} it matches
     * that field's normalised value only. Audited TEMPLATE_RECORDS_SEARCHED
     * with the query's SHA-256 prefix and the count, never values.
     */
    public RecordPageDto search(Integer templateId, String q, String field, Integer page, Integer size) {
        requireIngestionRead();
        if (templateId == null) {
            throw new IllegalArgumentException("templateId is required.");
        }
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be 0 or more.");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (pageSize < 1) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Template template = templateOwnership.requireReadable(templateId);
        String fieldName = field == null || field.isBlank() ? null : field.strip();
        if (fieldName != null && templateFields.listByTemplateId(templateId).stream()
                .map(TemplateFields::getFieldName).noneMatch(fieldName::equals)) {
            throw new IllegalArgumentException("\"" + truncate(fieldName, 80) + "\" is not a field of template "
                    + templateId + ".");
        }
        int bankId = currentUser.requireBankId();
        int userId = currentUser.requireUserId();
        boolean shared = isShared(template, bankId);

        Specification<TemplateRecord> spec = visibleOf(bankId, templateId, userId, shared, q, fieldName);
        Page<TemplateRecord> found = records.findAll(spec, PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("templateRecordId"))));

        UnaryOperator<Map<String, String>> mask = masking.screenValueMasker(templateId);
        List<RecordDto> items = found.getContent().stream()
                .map(r -> toDto(r, template.getName(), mask.apply(readValues(r))))
                .toList();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("queryHash", q == null || q.isBlank() ? null
                : Hashing.sha256(q.strip().toLowerCase(Locale.ROOT)).substring(0, 16));
        evidence.put("field", fieldName);
        evidence.put("count", found.getTotalElements());
        evidence.put("page", pageNumber);
        audit.success(AuditActions.TEMPLATE_RECORDS_SEARCHED, "Template", String.valueOf(templateId), null,
                evidence);
        return new RecordPageDto(items, pageNumber, pageSize, found.getTotalElements());
    }

    private static Specification<TemplateRecord> visibleOf(int bankId, int templateId, int userId, boolean shared,
                                                           String q, String fieldName) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("bankId"), bankId));
            where.add(cb.equal(root.get("templateId"), templateId));
            if (!shared) {
                where.add(cb.equal(root.get("ownerUserId"), userId));
            }
            if (fieldName != null) {
                String key = RecordText.key(q);
                if (!key.isEmpty()) {
                    Subquery<Long> keyed = query.subquery(Long.class);
                    Root<TemplateRecordKey> k = keyed.from(TemplateRecordKey.class);
                    keyed.select(k.get("templateRecordId")).where(
                            cb.equal(k.get("bankId"), bankId),
                            cb.equal(k.get("fieldName"), fieldName),
                            cb.like(k.get("keyValue"), contains(key), '\\'));
                    where.add(root.get("templateRecordId").in(keyed));
                }
            } else {
                for (String word : RecordText.words(q)) {
                    where.add(cb.like(root.get("searchText"), contains(word), '\\'));
                }
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    /** A LIKE pattern matching {@code text} anywhere, its wildcards escaped with a backslash. */
    static String contains(String text) {
        StringBuilder pattern = new StringBuilder("%");
        for (char c : text.toCharArray()) {
            if (c == '%' || c == '_' || c == '\\') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    // ----------------------------------------------------------------- read

    /**
     * {@code GET /api/doccreation/records/{id}}: one visible record, masked;
     * read-audited SENSITIVE_DATA_VIEWED (deduplicated per hour).
     */
    public RecordDto get(long recordId) {
        VisibleRecord found = requireVisible(recordId);
        audit.success(AuditActions.SENSITIVE_DATA_VIEWED, "TemplateRecord", String.valueOf(recordId), null,
                Map.of("view", "template-record"));
        return toDto(found.record(), found.template().getName(), found.masked());
    }

    /**
     * A visible record (see the class comment), or a 404 that cannot tell a
     * missing record from someone else's.
     */
    public VisibleRecord requireVisible(long recordId) {
        requireIngestionRead();
        int bankId = currentUser.requireBankId();
        int userId = currentUser.requireUserId();
        TemplateRecord record = records.findByTemplateRecordIdAndBankId(recordId, bankId).orElse(null);
        Template template = record == null ? null
                : templates.getAccessible(record.getTemplateId(), userId, bankId).orElse(null);
        if (record == null || template == null
                || (record.getOwnerUserId() != userId && !isShared(template, bankId))) {
            audit.record(AuditActions.notFoundProbe("TemplateRecord", recordId));
            throw new NoSuchElementException("Record " + recordId + " not found.");
        }
        Map<String, String> raw = readValues(record);
        return new VisibleRecord(record, template, raw,
                masking.screenValueMasker(record.getTemplateId()).apply(raw));
    }

    /**
     * The visible records of {@code templateId} whose {@code fieldName} key
     * equals {@code keyValue} (already normalised), newest first; empty when
     * the template is not readable by the caller.
     */
    public List<VisibleRecord> findByKey(int templateId, String fieldName, String keyValue) {
        if (keyValue == null || keyValue.isEmpty() || fieldName == null || fieldName.isBlank()) {
            return List.of();
        }
        int bankId = currentUser.requireBankId();
        int userId = currentUser.requireUserId();
        Optional<Template> template = templates.getAccessible(templateId, userId, bankId);
        if (template.isEmpty()) {
            return List.of();
        }
        UnaryOperator<Map<String, String>> mask = masking.screenValueMasker(templateId);
        return records.findByKey(bankId, templateId, userId, isShared(template.get(), bankId), fieldName, keyValue,
                        PageRequest.of(0, MAX_LINKED_MATCHES)).stream()
                .map(r -> {
                    Map<String, String> raw = readValues(r);
                    return new VisibleRecord(r, template.get(), raw, mask.apply(raw));
                })
                .toList();
    }

    /** The name of a template the caller can read; null otherwise. */
    public String readableTemplateName(int templateId) {
        return templates.getAccessible(templateId, currentUser.requireUserId(), currentUser.requireBankId())
                .map(Template::getName).orElse(null);
    }

    /** The caller holds INGESTION_READ (re-checked under the routes' own permission). */
    public void requireIngestionRead() {
        if (!perm.has(Permission.INGESTION_READ.name())) {
            throw new AccessDeniedException("Reading flow records needs ingestion:read.");
        }
    }

    // -------------------------------------------------------------- helpers

    private static boolean isShared(Template template, int bankId) {
        return template.getBankId() == bankId && template.getVisibility() == TemplateVisibility.BANK;
    }

    static RecordDto toDto(TemplateRecord record, String templateName, Map<String, String> values) {
        return new RecordDto(record.getTemplateRecordId(), record.getTemplateId(), templateName,
                record.getIngestionJobId(), record.getRowIndex(), values, record.getCreatedAt());
    }

    static Map<String, String> readValues(TemplateRecord record) {
        try {
            LinkedHashMap<String, String> values = JSON.readValue(record.getValuesJson(), VALUES);
            return values == null ? new LinkedHashMap<>() : values;
        } catch (IOException | IllegalArgumentException e) {
            return new LinkedHashMap<>();
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
