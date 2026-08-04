package com.modocument.infrastructure.extraction.connector;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.Connector;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.JobSecrets;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.DbSourceConfig;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.RawRecord;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.ExtractionTaskType;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.export.JdbcIdentifiers;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.worker.NodeIdentity;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * External-database reader. Chains watermark-ordered batch tasks so tables
 * of any size stream through bounded batches; the saved watermark enables
 * incremental re-extraction. Identifier and driver safety mirror
 * DatabaseExportService via the shared JdbcIdentifiers. Credentials arrive
 * per-run and stay in process memory, so batch tasks are node-affine.
 */
@Component
public class JdbcSourceConnector implements Connector {

    static final String PAYLOAD_AFTER_WATERMARK = "afterWatermark";
    static final String PAYLOAD_BATCH = "batch";
    static final String SECRET_USERNAME = "dbUsername";
    static final String SECRET_PASSWORD = "dbPassword";

    private final JobSecrets secrets;
    private final NodeIdentity node;
    private final ExtractionProperties properties;
    private final ExtractionAuditLog audit;
    private final OutboundHostPolicy egress;

    public JdbcSourceConnector(JobSecrets secrets, NodeIdentity node,
                               ExtractionProperties properties, ExtractionAuditLog audit,
                               OutboundHostPolicy egress) {
        this.secrets = secrets;
        this.node = node;
        this.properties = properties;
        this.audit = audit;
        this.egress = egress;
    }

    @Override
    public SourceType type() {
        return SourceType.DATABASE;
    }

    @Override
    public List<ExtractionTask> seedTasks(ExtractionJob job, ExtractionSource source,
                                          ConnectorState state) {
        String afterWatermark = job.isIncremental() && state != null
                && state.getLastWatermark() != null ? state.getLastWatermark() : "";
        return List.of(newBatchTask(job, source, afterWatermark, 1));
    }

    @Override
    public ConnectorResult execute(ExtractionTask task, ExtractionJob job,
                                   ExtractionSource source, ConnectorState state) {
        DbSourceConfig config = source.getDb();
        // SSRF guard: refuse to open a JDBC connection to an internal host.
        egress.checkJdbcUrl(config.getJdbcUrl());
        String table = JdbcIdentifiers.requireValidIdentifier(config.getTable(), "table");
        String watermark = config.getWatermarkColumn() == null ? null
                : JdbcIdentifiers.requireValidIdentifier(config.getWatermarkColumn(), "watermarkColumn");
        for (String column : config.getColumns()) {
            JdbcIdentifiers.requireValidIdentifier(column, "column");
        }

        Map<String, String> jobSecrets = secrets.get(job.getId()).orElse(Map.of());
        JdbcTemplate jdbc = new JdbcTemplate(JdbcIdentifiers.dataSource(
                config.getJdbcUrl(),
                jobSecrets.get(SECRET_USERNAME),
                jobSecrets.get(SECRET_PASSWORD)));
        int batchSize = Math.max(1, config.getBatchSize());
        jdbc.setFetchSize(Math.min(batchSize, 1000));
        jdbc.setMaxRows(batchSize);

        String columnList = config.getColumns().isEmpty() ? "*"
                : config.getColumns().stream()
                        .map(JdbcIdentifiers::quote)
                        .collect(Collectors.joining(", "));
        String afterWatermark = task.getPayload().getOrDefault(PAYLOAD_AFTER_WATERMARK, "");
        int batch = Integer.parseInt(task.getPayload().getOrDefault(PAYLOAD_BATCH, "1"));

        String sql;
        Object[] args;
        if (watermark != null && !afterWatermark.isEmpty()) {
            sql = "SELECT " + columnList + " FROM " + JdbcIdentifiers.quote(table)
                    + " WHERE " + JdbcIdentifiers.quote(watermark) + " > ?"
                    + " ORDER BY " + JdbcIdentifiers.quote(watermark);
            args = new Object[]{afterWatermark};
        } else if (watermark != null) {
            sql = "SELECT " + columnList + " FROM " + JdbcIdentifiers.quote(table)
                    + " ORDER BY " + JdbcIdentifiers.quote(watermark);
            args = new Object[0];
        } else {
            sql = "SELECT " + columnList + " FROM " + JdbcIdentifiers.quote(table);
            args = new Object[0];
        }

        List<Map<String, String>> rows = jdbc.query(sql, (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                row.put(meta.getColumnLabel(i), value == null ? "" : String.valueOf(value));
            }
            return row;
        }, args);

        String target = JdbcIdentifiers.describeTarget(config.getJdbcUrl()) + "#" + table;
        audit.add(ExtractionAuditEvent.of(job.getId(), source.getId(), AuditAction.FETCH, target,
                rows.size() + " row(s)", "batch " + batch));

        // Manifest raw record: provenance without duplicating row payloads.
        RawRecord manifest = new RawRecord();
        manifest.setJobId(job.getId());
        manifest.setSourceId(source.getId());
        manifest.setUri(target);
        manifest.setContentType("application/x-jdbc-batch");
        manifest.setBody("{\"batch\":" + batch + ",\"rows\":" + rows.size() + "}");
        manifest.setFetchedAt(Instant.now());

        List<ExtractionTask> followUps = List.of();
        Map<String, String> stateUpdates = new LinkedHashMap<>();
        if (watermark != null && rows.size() == batchSize) {
            String lastValue = rows.get(rows.size() - 1).getOrDefault(watermark, "");
            if (!lastValue.isEmpty() && !lastValue.equals(afterWatermark)) {
                followUps = List.of(newBatchTask(job, source, lastValue, batch + 1));
            }
        }
        if (watermark != null && !rows.isEmpty()) {
            stateUpdates.put("watermark", rows.get(rows.size() - 1).getOrDefault(watermark, ""));
        }
        if (watermark == null && rows.size() == batchSize) {
            audit.add(ExtractionAuditEvent.of(job.getId(), source.getId(), AuditAction.ERROR, target,
                    "truncated", "Batch cap reached without a watermarkColumn; configure one to "
                            + "stream the full table."));
        }

        return new ConnectorResult(
                List.of(new ConnectorResult.FetchedItem(manifest, rows)), followUps, stateUpdates);
    }

    private ExtractionTask newBatchTask(ExtractionJob job, ExtractionSource source,
                                        String afterWatermark, int batch) {
        ExtractionTask task = new ExtractionTask();
        task.setJobId(job.getId());
        task.setSourceId(source.getId());
        task.setType(ExtractionTaskType.QUERY_DB_BATCH);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_AFTER_WATERMARK, afterWatermark);
        payload.put(PAYLOAD_BATCH, String.valueOf(batch));
        task.setPayload(payload);
        task.setDedupKey("db:" + afterWatermark);
        task.setMaxAttempts(properties.getWorker().getMaxAttempts());
        // Credentials live only in this node's memory.
        task.setNodeAffinity(node.id());
        return task;
    }
}
