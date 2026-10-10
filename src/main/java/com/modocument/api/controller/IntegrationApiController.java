package com.modocument.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.application.usecase.IntegrationApiException;
import com.modocument.application.usecase.IntegrationApiUseCase;
import com.modocument.application.usecase.UploadOwnership;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadContentChecker;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.ingestion.IngestionJobRunner;
import com.modocument.infrastructure.integration.IdempotencyStore;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Integration API v1 for bank systems (ws26, contracts §3 SE2) — machine
 * callers only (SecurityConfig), each route guarded by its client scope:
 * <ul>
 *   <li>{@code GET /templates} (INTEGRATION_INGEST) — bank-shared templates + fields;</li>
 *   <li>{@code POST /records} (INTEGRATION_INGEST) — JSON records → 202 {@code {jobId}};</li>
 *   <li>{@code POST /files} (INTEGRATION_INGEST) — multipart {@code file} +
 *       {@code templateId} → 202 {@code {jobId}};</li>
 *   <li>{@code GET /jobs/{id}}, {@code /jobs/{id}/results?format=json|csv},
 *       {@code /jobs/{id}/errors} (INTEGRATION_RESULTS);</li>
 *   <li>{@code GET /documents/{id}[?format=pdf|docx]} (INTEGRATION_DOCS) — released only;</li>
 *   <li>{@code GET /events?after=&limit=} (INTEGRATION_EVENTS).</li>
 * </ul>
 * POSTs require {@code Idempotency-Key} (same key + same request → the stored
 * answer; same key + different request → 409). Bodies and files are capped
 * ({@code modocument.integration.api.*}). Another bank's ids are 404. Every
 * call is metered per client and audited with the client as actor
 * ({@link MachineApi}). Errors: {@code {error, code, message, requestId}}.
 */
@RestController
@MachineApi
@Tag(name = "Integration API v1", description = "Machine API for bank systems (API clients)")
public class IntegrationApiController {

    static final String BASE = "/api/integration/v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationApiUseCase useCase;
    private final CurrentClientProvider clients;
    private final IdempotencyStore idempotency;
    private final IntegrationClientProperties properties;
    private final IngestionJobUseCase ingestion;
    private final IngestionJobRunner jobRunner;
    private final UploadStorage uploadStorage;
    private final UploadOwnership uploadOwnership;
    private final SupportedDocumentTypes supportedTypes;
    private final UploadContentChecker contentChecker;

    public IntegrationApiController(IntegrationApiUseCase useCase,
                                    CurrentClientProvider clients,
                                    IdempotencyStore idempotency,
                                    IntegrationClientProperties properties,
                                    IngestionJobUseCase ingestion,
                                    IngestionJobRunner jobRunner,
                                    UploadStorage uploadStorage,
                                    UploadOwnership uploadOwnership,
                                    SupportedDocumentTypes supportedTypes,
                                    UploadContentChecker contentChecker) {
        this.useCase = useCase;
        this.clients = clients;
        this.idempotency = idempotency;
        this.properties = properties;
        this.ingestion = ingestion;
        this.jobRunner = jobRunner;
        this.uploadStorage = uploadStorage;
        this.uploadOwnership = uploadOwnership;
        this.supportedTypes = supportedTypes;
        this.contentChecker = contentChecker;
    }

    /** OpenAPI shape of {@code POST /records}. */
    @Schema(name = "IntegrationRecordsRequest")
    public record RecordsRequest(int templateId, String externalBatchId,
                                 List<Map<String, Object>> records) {
    }

    // ================================================================ ingest

    @RequiresPermission(Permission.INTEGRATION_INGEST)
    @GetMapping(BASE + "/templates")
    public Map<String, Object> templates() {
        return Map.of("templates", useCase.templates(clients.require()));
    }

    @RequiresPermission(Permission.INTEGRATION_INGEST)
    @PostMapping(value = BASE + "/records", consumes = MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = RecordsRequest.class)))
    public ResponseEntity<?> records(HttpServletRequest request,
                                     @RequestHeader(value = IdempotencyStore.HEADER,
                                             required = false) String idempotencyKey) {
        ClientPrincipal client = clients.require();
        byte[] body = readCapped(request, properties.getApi().getMaxBodyBytes());
        String hash = IdempotencyStore.requestHash("POST", BASE + "/records", body);
        return idempotent(client, idempotencyKey, hash, () -> {
            RecordsRequest parsed = parseRecords(body);
            int jobId = useCase.createFromRecords(client, parsed.templateId(),
                    parsed.externalBatchId(), parsed.records());
            return accepted(jobId);
        });
    }

    @RequiresPermission(Permission.INTEGRATION_INGEST)
    @PostMapping(value = BASE + "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> files(@RequestParam("file") MultipartFile file,
                                   @RequestParam("templateId") int templateId,
                                   @RequestHeader(value = IdempotencyStore.HEADER,
                                           required = false) String idempotencyKey) {
        ClientPrincipal client = clients.require();
        if (file == null || file.isEmpty()) {
            throw IntegrationApiException.badRequest("FILE_REQUIRED", "No file uploaded.");
        }
        if (file.getSize() > properties.getApi().getMaxFileBytes()) {
            throw IntegrationApiException.tooLarge("The file exceeds "
                    + properties.getApi().getMaxFileBytes() + " bytes.");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String sha256 = sha256(file);
        String hash = IdempotencyStore.requestHash("POST", BASE + "/files",
                String.valueOf(templateId).getBytes(StandardCharsets.UTF_8),
                originalName.getBytes(StandardCharsets.UTF_8),
                sha256.getBytes(StandardCharsets.UTF_8));
        return idempotent(client, idempotencyKey, hash, () -> {
            useCase.requireSharedTemplate(client, templateId);
            if (!supportedTypes.isSupported(originalName)) {
                throw IntegrationApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                        "Unsupported file type. Supported types: "
                                + supportedTypes.describeSupported() + ".");
            }
            contentChecker.requireContentMatchesExtension(originalName, file);
            uploadOwnership.rejectDuplicate(originalName, sha256);
            String storedName = uploadStorage.store(file);
            uploadOwnership.record(storedName, originalName, file.getSize(), sha256);
            IngestionJob job = ingestion.create(storedName, templateId,
                    "api-client:" + client.apiClientId());
            jobRunner.enqueueResume(job.getIngestionJobId(), JobStep.VALIDATED);
            return accepted(job.getIngestionJobId());
        });
    }

    // ================================================================ results

    @RequiresPermission(Permission.INTEGRATION_RESULTS)
    @GetMapping(BASE + "/jobs/{id}")
    public Map<String, Object> job(@PathVariable int id) {
        return useCase.jobStatus(clients.require(), id);
    }

    @RequiresPermission(Permission.INTEGRATION_RESULTS)
    @GetMapping(BASE + "/jobs/{id}/results")
    public ResponseEntity<?> results(@PathVariable int id,
                                     @RequestParam(defaultValue = "json") String format) {
        IntegrationApiUseCase.Results results = useCase.results(clients.require(), id, format);
        if (results.file() != null) {
            return fileResponse(results);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.json());
    }

    @RequiresPermission(Permission.INTEGRATION_RESULTS)
    @GetMapping(BASE + "/jobs/{id}/errors")
    public ResponseEntity<Map<String, Object>> errors(@PathVariable int id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(useCase.errors(clients.require(), id));
    }

    // ================================================================ documents

    @RequiresPermission(Permission.INTEGRATION_DOCS)
    @GetMapping(BASE + "/documents/{id}")
    public ResponseEntity<?> document(@PathVariable int id,
                                      @RequestParam(required = false) String format) {
        ClientPrincipal client = clients.require();
        if (format == null || format.isBlank()) {
            return ResponseEntity.ok(useCase.document(client, id));
        }
        return fileResponse(useCase.documentFile(client, id, format));
    }

    // ================================================================ events

    @RequiresPermission(Permission.INTEGRATION_EVENTS)
    @GetMapping(BASE + "/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "0") long after,
                                      @RequestParam(required = false) Integer limit) {
        if (after < 0) {
            throw IntegrationApiException.badRequest("INVALID_CURSOR", "after must be >= 0.");
        }
        IntegrationClientProperties.Api api = properties.getApi();
        int size = limit == null ? api.getEventsDefaultLimit()
                : Math.max(1, Math.min(limit, api.getEventsMaxLimit()));
        return useCase.events(clients.require(), after, size);
    }

    // ================================================================ plumbing

    private ResponseEntity<?> idempotent(ClientPrincipal client, String key, String hash,
                                         Supplier<ResponseEntity<Map<String, Object>>> action) {
        IdempotencyStore.Outcome outcome = idempotency.begin(client, key, hash);
        if (outcome.isReplay()) {
            return ResponseEntity.status(outcome.replayStatus())
                    .header("Idempotent-Replayed", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outcome.replayBody() == null ? "{}" : outcome.replayBody());
        }
        try {
            ResponseEntity<Map<String, Object>> response = action.get();
            idempotency.complete(outcome.reservationId(), response.getStatusCode().value(),
                    JSON.writeValueAsString(response.getBody()));
            return response;
        } catch (RuntimeException | IOException failure) {
            idempotency.release(outcome.reservationId());
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException((IOException) failure);
        }
    }

    private static ResponseEntity<Map<String, Object>> accepted(int jobId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        return ResponseEntity.accepted()
                .location(URI.create(BASE + "/jobs/" + jobId))
                .body(body);
    }

    private static ResponseEntity<byte[]> fileResponse(IntegrationApiUseCase.Results results) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(results.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(results.contentType()))
                .body(results.file());
    }

    /** The body, refusing (413) anything above {@code max} bytes without reading it all. */
    static byte[] readCapped(HttpServletRequest request, long max) {
        long declared = request.getContentLengthLong();
        if (declared > max) {
            throw IntegrationApiException.tooLarge("The request body exceeds " + max + " bytes.");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            if (bytes.length > max) {
                throw IntegrationApiException.tooLarge("The request body exceeds " + max
                        + " bytes.");
            }
            return bytes;
        } catch (IOException e) {
            throw IntegrationApiException.badRequest("UNREADABLE_BODY",
                    "The request body could not be read.");
        }
    }

    /** {@code {templateId, externalBatchId, records:[{…}]}}, values scalar only. */
    private RecordsRequest parseRecords(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw IntegrationApiException.badRequest("INVALID_JSON",
                    "The request body is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw IntegrationApiException.badRequest("INVALID_REQUEST",
                    "The body must be a JSON object {templateId, externalBatchId, records}.");
        }
        JsonNode templateId = root.get("templateId");
        if (templateId == null || !templateId.canConvertToInt() || !templateId.isIntegralNumber()) {
            throw IntegrationApiException.badRequest("INVALID_REQUEST",
                    "templateId (an integer) is required.");
        }
        JsonNode batch = root.get("externalBatchId");
        String externalBatchId = batch == null || batch.isNull() ? null : batch.asText();
        if (externalBatchId != null && externalBatchId.length() > 200) {
            throw IntegrationApiException.badRequest("INVALID_REQUEST",
                    "externalBatchId must be at most 200 characters.");
        }
        JsonNode recordsNode = root.get("records");
        if (recordsNode == null || !recordsNode.isArray() || recordsNode.isEmpty()) {
            throw IntegrationApiException.badRequest("INVALID_REQUEST",
                    "records must be a non-empty array of objects.");
        }
        int max = properties.getApi().getMaxRecords();
        if (recordsNode.size() > max) {
            throw IntegrationApiException.tooLarge("At most " + max + " records per request.");
        }
        List<Map<String, Object>> records = new ArrayList<>(recordsNode.size());
        for (JsonNode node : recordsNode) {
            if (!node.isObject()) {
                throw IntegrationApiException.badRequest("INVALID_REQUEST",
                        "Every record must be a JSON object.");
            }
            Map<String, Object> record = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isContainerNode()) {
                    throw IntegrationApiException.badRequest("INVALID_REQUEST",
                            "Record values must be strings, numbers, booleans or null ("
                                    + field.getKey() + ").");
                }
                record.put(field.getKey(), value.isNull() ? null
                        : value.isNumber() ? value.numberValue()
                        : value.isBoolean() ? value.booleanValue() : value.asText());
            }
            records.add(record);
        }
        return new RecordsRequest(templateId.intValue(), externalBatchId, records);
    }

    private static String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
