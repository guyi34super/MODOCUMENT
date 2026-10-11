package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.dto.AuditBankCounts;
import com.modocument.application.dto.AuditEventView;
import com.modocument.application.dto.AuditPage;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditLogReader;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.application.privacy.Masker;
import com.modocument.application.privacy.MaskingPolicy;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The audit viewer (ws28, contracts §3): {@code GET /api/admin/audit} (one
 * bank's events, filtered, keyset-paged newest first),
 * {@code GET /api/admin/audit/export} (the same filters as CSV),
 * {@code GET /api/admin/audit/actions} (the filter catalogue) and the
 * platform console's {@code GET /api/platform/audit[/counts]}.
 *
 * <p><b>Tenancy.</b> The bank is ALWAYS the caller's (from its token);
 * nothing in a request names a bank, an id or a cursor of another bank
 * finds nothing (the explicit bank predicate, plus RLS on PostgreSQL). The
 * only cross-bank read is {@link #platformCounts}: counts per bank, read as
 * SYSTEM in its own read-only transaction (a registered {@code runAsSystem}
 * call site, see {@link TenantContext}), platform bank only.
 *
 * <p><b>Masking.</b> The value snapshots pass through the PII masking of
 * the caller's channel: an ADMIN reads the trail like the working screens
 * ({@code SCREEN_WORKING}: raw unless the bank force-masks), every other
 * reader (APPROVER, PLATFORM_ADMIN) like a read-only screen (masked), and
 * the CSV export is the EXPORT channel (masked for everyone). Masking walks
 * the JSON: fields named like an email/phone/name/NIC/account mask by that
 * kind, every other string masks the NIC/account/email SHAPES it contains.
 * The actor columns (who did it) are the trail's purpose and stay as they are.
 *
 * <p><b>Self-audit.</b> Viewing is audited {@code AUDIT_LOG_VIEWED} (the
 * recorder keeps one row per actor per 15 minutes), exporting
 * {@code AUDIT_LOG_EXPORTED} (every time). Reading the trail never audits
 * reads of the rows it shows — no recursion.
 */
@Service
public class AuditLogUseCase {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /** The CSV export's hard row cap (contracts §3). */
    public static final int EXPORT_MAX_ROWS = 50_000;
    static final int EXPORT_PAGE = 1_000;
    static final Duration DEFAULT_COUNTS_RANGE = Duration.ofHours(24);
    static final Duration MAX_COUNTS_RANGE = Duration.ofDays(400);

    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{0,59}$");
    private static final Pattern EMAIL_KEY = Pattern.compile("(?i).*e-?mail.*");
    private static final Pattern PHONE_KEY = Pattern.compile("(?i).*(phone|mobile|msisdn).*");
    private static final Pattern NIC_KEY = Pattern.compile("(?i).*(nic|nationalid|national_id|ssn).*");
    private static final Pattern ACCOUNT_KEY = Pattern.compile("(?i).*(account|iban).*");
    private static final Pattern NAME_KEY =
            Pattern.compile("(?i)^(display_?name|full_?name|first_?name|last_?name|surname|customer_?name)$");

    private final AuditLogReader reader;
    private final AuditRecorder audit;
    private final CurrentUserProvider currentUser;
    private final PlatformBankLookup platformBank;
    private final MaskingPolicy maskingPolicy;
    private final BankJpaRepository banks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnlyNewTx;

    public AuditLogUseCase(AuditLogReader reader, AuditRecorder audit,
                           CurrentUserProvider currentUser, PlatformBankLookup platformBank,
                           MaskingPolicy maskingPolicy, BankJpaRepository banks,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.audit = audit;
        this.currentUser = currentUser;
        this.platformBank = platformBank;
        this.maskingPolicy = maskingPolicy;
        this.banks = banks;
        this.objectMapper = objectMapper;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // NEW + read-only, begun under runAsSystem: the tenant binds at
        // transaction begin, so joining a request transaction would count
        // only the platform bank's own rows.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.readOnlyNewTx = template;
    }

    /** The raw request filters, exactly as they arrived (validated here). */
    public record Filters(String actor, List<String> actions, String entityType, String entityId,
                          String outcome, String from, String to) {

        public static Filters none() {
            return new Filters(null, null, null, null, null, null, null);
        }
    }

    /** What the CSV export streams to, row by row. */
    public interface CsvSink {
        void write(String line);

        void flush();
    }

    /** The prepared export: its size decision, then {@link #stream}. */
    public record Export(int rows, long matched, boolean truncated, Runnable stream) {
    }

    // ---------------------------------------------------------------- reads

    /** {@code GET /api/admin/audit}: the caller's bank. */
    public AuditPage list(Filters filters, String cursor, Integer limit) {
        return listFor(currentUser.requireBankId(), filters, cursor, limit, "bank");
    }

    /** {@code GET /api/platform/audit}: the platform bank's own events. */
    public AuditPage listPlatform(Filters filters, String cursor, Integer limit) {
        return listFor(requirePlatformCaller(), filters, cursor, limit, "platform");
    }

    private AuditPage listFor(int bankId, Filters filters, String cursor, Integer limit,
                              String scope) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        AuditLogReader.Query query = query(bankId, filters);
        Cursor after = Cursor.decode(cursor);
        if (after != null) {
            query = query.after(after.at(), after.id());
        }
        List<AuditEvent> rows = reader.page(query, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<AuditEvent> page = more ? rows.subList(0, pageSize) : rows;
        boolean masked = maskingPolicy.mask(screenChannel(), callerRole());
        Map<Integer, String> emails = emailsFor(bankId, page);
        List<AuditEventView> views = new ArrayList<>(page.size());
        for (AuditEvent event : page) {
            views.add(view(event, emails, masked));
        }
        String next = more ? Cursor.encode(page.get(page.size() - 1)) : null;

        Map<String, Object> viewed = describe(filters);
        viewed.put("scope", scope);
        audit.record(AuditEventSpec.builder(AuditActions.AUDIT_LOG_VIEWED, AuditOutcome.SUCCESS)
                .entity("AuditLog", null)
                .newValues(viewed)
                .build());
        return new AuditPage(views, next);
    }

    /** {@code GET /api/admin/audit/actions}: the filter picker's catalogue. */
    public List<AuditActions.Action> actions() {
        return AuditActions.all();
    }

    // ---------------------------------------------------------------- export

    /**
     * Prepares the caller's CSV export: counts the matches (the cap decision
     * is taken before a byte is written), records AUDIT_LOG_EXPORTED and
     * returns the streaming step. Above {@value #EXPORT_MAX_ROWS} matches
     * the NEWEST {@value #EXPORT_MAX_ROWS} are exported and the export says
     * it was truncated.
     */
    public Export export(Filters filters, CsvSink sink) {
        int bankId = currentUser.requireBankId();
        AuditLogReader.Query query = query(bankId, filters);
        long matched = reader.count(query);
        int rows = (int) Math.min(matched, EXPORT_MAX_ROWS);
        boolean truncated = matched > EXPORT_MAX_ROWS;
        boolean masked = maskingPolicy.mask(MaskingPolicy.Channel.EXPORT, callerRole());

        Map<String, Object> exported = describe(filters);
        exported.put("rowCount", rows);
        exported.put("matched", matched);
        exported.put("truncated", truncated);
        exported.put("format", "CSV");
        audit.record(AuditEventSpec.builder(AuditActions.AUDIT_LOG_EXPORTED, AuditOutcome.SUCCESS)
                .entity("AuditLog", null)
                .newValues(exported)
                .build());

        Runnable stream = () -> {
            sink.write(CSV_HEADER);
            int written = 0;
            AuditLogReader.Query next = query;
            while (written < rows) {
                int want = Math.min(EXPORT_PAGE, rows - written);
                List<AuditEvent> page = reader.page(next, want);
                if (page.isEmpty()) {
                    break;
                }
                Map<Integer, String> emails = emailsFor(bankId, page);
                for (AuditEvent event : page) {
                    sink.write(csvLine(view(event, emails, masked)));
                }
                written += page.size();
                AuditEvent last = page.get(page.size() - 1);
                next = next.after(last.getOccurredAt(), last.getAuditEventId());
                sink.flush();
                if (page.size() < want) {
                    break;
                }
            }
            sink.flush();
        };
        return new Export(rows, matched, truncated, stream);
    }

    static final String CSV_HEADER = "occurredAt,id,action,outcome,actorUserId,actorEmail,"
            + "actorRole,entityType,entityId,ip,requestId,oldValues,newValues";

    String csvLine(AuditEventView v) {
        return String.join(",",
                csv(v.occurredAt() == null ? null : v.occurredAt().toString()),
                csv(v.id() == null ? null : v.id().toString()),
                csv(v.action()), csv(v.outcome()),
                csv(v.actorUserId() == null ? null : v.actorUserId().toString()),
                csv(v.actorEmail()), csv(v.actorRole()), csv(v.entityType()), csv(v.entityId()),
                csv(v.ip()), csv(v.requestId()),
                csv(v.oldValues() == null ? null : v.oldValues().toString()),
                csv(v.newValues() == null ? null : v.newValues().toString()));
    }

    /** RFC 4180 quoting plus spreadsheet-formula neutralization (CWE-1236). */
    static String csv(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    // ------------------------------------------------------- platform counts

    /**
     * {@code GET /api/platform/audit/counts}: every bank's totals for
     * {@code [from, to)} (default: the last 24 h; at most 400 days) —
     * COUNTS ONLY. Read as SYSTEM in its own read-only transaction.
     */
    public AuditBankCounts platformCounts(String fromParam, String toParam) {
        requirePlatformCaller();
        Instant to = toParam == null || toParam.isBlank() ? Instant.now() : instant(toParam, "to");
        Instant from = fromParam == null || fromParam.isBlank()
                ? to.minus(DEFAULT_COUNTS_RANGE) : instant(fromParam, "from");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        if (Duration.between(from, to).compareTo(MAX_COUNTS_RANGE) > 0) {
            throw new IllegalArgumentException("The range may span at most 400 days.");
        }
        List<AuditBankCounts.Bank> result;
        try {
            result = TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status -> {
                Map<Integer, AuditLogReader.BankCount> counts = new HashMap<>();
                for (AuditLogReader.BankCount count : reader.countsByBank(from, to)) {
                    counts.put(count.bankId(), count);
                }
                List<AuditBankCounts.Bank> list = new ArrayList<>();
                for (Bank bank : banks.findAll(Sort.by("code"))) {
                    AuditLogReader.BankCount c = counts.get(bank.getBankId());
                    list.add(new AuditBankCounts.Bank(bank.getBankId(), bank.getCode(),
                            c == null ? 0 : c.total(), c == null ? 0 : c.denied(),
                            c == null ? 0 : c.failed()));
                }
                return list;
            }));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Platform audit counts failed.", e);
        }
        audit.record(AuditEventSpec.builder(AuditActions.AUDIT_LOG_VIEWED, AuditOutcome.SUCCESS)
                .entity("AuditLog", null)
                .newValues(Map.of("scope", "platform-counts", "from", from.toString(),
                        "to", to.toString()))
                .build());
        return new AuditBankCounts(from, to, result);
    }

    /** The platform routes: AUDIT_READ is not enough, the caller must sit in the platform bank. */
    private int requirePlatformCaller() {
        int bankId = currentUser.requireBankId();
        if (!platformBank.isPlatformBank(bankId)) {
            throw new AccessDeniedException("The platform audit is for the platform bank only.");
        }
        return bankId;
    }

    // ---------------------------------------------------------------- helpers

    private AuditLogReader.Query query(int bankId, Filters filters) {
        Filters f = filters == null ? Filters.none() : filters;
        Integer actorUserId = null;
        String actorEmail = null;
        String actor = blankToNull(f.actor());
        if (actor != null) {
            if (actor.chars().allMatch(Character::isDigit) && actor.length() <= 9) {
                actorUserId = Integer.parseInt(actor);
            } else {
                if (actor.length() > 320 || actor.indexOf('@') < 1) {
                    throw new IllegalArgumentException("actor must be a user id or an email address.");
                }
                actorEmail = actor.toLowerCase(Locale.ROOT);
                // An email names the user's events too (most rows carry the
                // acting user's id, not their email).
                actorUserId = reader.userIdByEmail(bankId, actorEmail).orElse(null);
            }
        }
        Set<String> actions = new LinkedHashSet<>();
        if (f.actions() != null) {
            for (String raw : f.actions()) {
                for (String part : raw == null ? new String[0] : raw.split(",")) {
                    String action = part.trim().toUpperCase(Locale.ROOT);
                    if (action.isEmpty()) {
                        continue;
                    }
                    if (!ACTION.matcher(action).matches()) {
                        throw new IllegalArgumentException("Unknown action format: " + truncate(part, 60));
                    }
                    actions.add(action);
                }
            }
            if (actions.size() > 50) {
                throw new IllegalArgumentException("At most 50 actions per query.");
            }
        }
        String entityType = blankToNull(f.entityType());
        if (entityType != null && entityType.length() > 40) {
            throw new IllegalArgumentException("entityType is at most 40 characters.");
        }
        String entityId = blankToNull(f.entityId());
        if (entityId != null && entityId.length() > 64) {
            throw new IllegalArgumentException("entityId is at most 64 characters.");
        }
        AuditOutcome outcome = null;
        String rawOutcome = blankToNull(f.outcome());
        if (rawOutcome != null) {
            try {
                outcome = AuditOutcome.valueOf(rawOutcome.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("outcome must be SUCCESS, DENIED or FAILED.");
            }
        }
        Instant from = blankToNull(f.from()) == null ? null : instant(f.from(), "from");
        Instant to = blankToNull(f.to()) == null ? null : instant(f.to(), "to");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        return new AuditLogReader.Query(bankId, actorUserId,
                actorUserId != null && actorEmail == null ? null : actorEmail,
                actions.isEmpty() ? null : actions, entityType, entityId, outcome, from, to,
                null, null);
    }

    /** ISO-8601 instant ({@code 2026-10-01T00:00:00Z}, with offset) or a date (UTC midnight). */
    static Instant instant(String value, String name) {
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // try the other shapes
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a date
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 date or instant.");
        }
    }

    private Map<Integer, String> emailsFor(int bankId, List<AuditEvent> page) {
        Set<Integer> missing = new HashSet<>();
        for (AuditEvent event : page) {
            if (event.getActorEmail() == null && event.getActorUserId() != null) {
                missing.add(event.getActorUserId());
            }
        }
        return missing.isEmpty() ? Map.of() : reader.userEmails(bankId, missing);
    }

    private AuditEventView view(AuditEvent event, Map<Integer, String> emails, boolean masked) {
        String email = event.getActorEmail() != null ? event.getActorEmail()
                : event.getActorUserId() == null ? null : emails.get(event.getActorUserId());
        return new AuditEventView(event.getAuditEventId(), event.getOccurredAt(),
                event.getAction(), event.getOutcome() == null ? null : event.getOutcome().name(),
                event.getActorUserId(), email, event.getActorRole(), event.getEntityType(),
                event.getEntityId(), event.getIp(), event.getRequestId(),
                values(event.getOldValues(), masked), values(event.getNewValues(), masked));
    }

    private JsonNode values(String json, boolean masked) {
        if (json == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            node = TextNode.valueOf(json);
        }
        return masked ? mask(node, null) : node;
    }

    /** Masks every string leaf: by its field name's kind, else by value shape. */
    JsonNode mask(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), mask(field.getValue(), field.getKey()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                copy.add(mask(element, fieldName));
            }
            return copy;
        }
        if (node.isTextual()) {
            PiiKind kind = kindOf(fieldName);
            String text = node.asText();
            return TextNode.valueOf(kind == null ? Masker.maskShapes(text) : Masker.mask(kind, text));
        }
        return node;
    }

    private static PiiKind kindOf(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        if (EMAIL_KEY.matcher(fieldName).matches()) {
            return PiiKind.EMAIL;
        }
        if (PHONE_KEY.matcher(fieldName).matches()) {
            return PiiKind.PHONE;
        }
        if (NIC_KEY.matcher(fieldName).matches()) {
            return PiiKind.NIC;
        }
        if (ACCOUNT_KEY.matcher(fieldName).matches()) {
            return PiiKind.ACCOUNT_NUMBER;
        }
        if (NAME_KEY.matcher(fieldName).matches()) {
            return PiiKind.NAME;
        }
        return null;
    }

    /** ADMIN reads like the working screens, every other reader like a read-only screen. */
    private MaskingPolicy.Channel screenChannel() {
        return callerRole() == Role.ADMIN ? MaskingPolicy.Channel.SCREEN_WORKING
                : MaskingPolicy.Channel.SCREEN_READONLY;
    }

    private Role callerRole() {
        try {
            return currentUser.role();
        } catch (IllegalStateException noRole) {
            return null;
        }
    }

    /** The filters as recorded on AUDIT_LOG_VIEWED / AUDIT_LOG_EXPORTED (no values). */
    private static Map<String, Object> describe(Filters filters) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (filters == null) {
            return values;
        }
        putIf(values, "actor", filters.actor());
        if (filters.actions() != null && !filters.actions().isEmpty()) {
            values.put("actions", filters.actions());
        }
        putIf(values, "entityType", filters.entityType());
        putIf(values, "entityId", filters.entityId());
        putIf(values, "outcome", filters.outcome());
        putIf(values, "from", filters.from());
        putIf(values, "to", filters.to());
        return values;
    }

    private static void putIf(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, truncate(value.trim(), 320));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * The opaque keyset cursor: base64url of {@code <epochSecond>.<nano>|<uuid>}
     * — the last row of the previous page. It names no bank: decoded under
     * another bank it simply positions that bank's own walk.
     */
    record Cursor(Instant at, UUID id) {

        static String encode(AuditEvent last) {
            Instant at = last.getOccurredAt();
            String raw = at.getEpochSecond() + "." + at.getNano() + "|" + last.getAuditEventId();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()),
                        StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                int dot = raw.indexOf('.');
                if (bar < 0 || dot < 0 || dot > bar) {
                    throw new IllegalArgumentException("malformed");
                }
                long seconds = Long.parseLong(raw.substring(0, dot));
                int nanos = Integer.parseInt(raw.substring(dot + 1, bar));
                return new Cursor(Instant.ofEpochSecond(seconds, nanos),
                        UUID.fromString(raw.substring(bar + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("cursor is not a cursor from a previous page.");
            }
        }
    }

}
