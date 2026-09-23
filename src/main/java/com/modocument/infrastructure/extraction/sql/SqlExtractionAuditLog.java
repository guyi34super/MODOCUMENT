package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionAuditEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** SQL-backed append-only audit trail, cursor-paged by the identity id. */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlExtractionAuditLog implements ExtractionAuditLog {

    private final ExtractionAuditEventJpaRepository repository;

    public SqlExtractionAuditLog(ExtractionAuditEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void add(ExtractionAuditEvent event) {
        ExtractionAuditEventEntity entity = new ExtractionAuditEventEntity();
        entity.setJobId(event.getJobId());
        entity.setSourceId(event.getSourceId());
        entity.setAction(event.getAction());
        entity.setUri(event.getUri());
        entity.setOutcome(event.getOutcome());
        entity.setDetail(event.getDetail());
        entity.setTimestamp(event.getTimestamp());
        ExtractionAuditEventEntity saved = repository.save(entity);
        event.setId(String.valueOf(saved.getId()));
    }

    @Override
    public List<ExtractionAuditEvent> findByJob(String jobId, String afterId, int limit) {
        Long after = JsonColumns.parseId(afterId);
        return repository.findBatch(jobId, after == null ? 0L : after, PageRequest.of(0, limit))
                .stream().map(SqlExtractionAuditLog::toDomain).toList();
    }

    private static ExtractionAuditEvent toDomain(ExtractionAuditEventEntity entity) {
        ExtractionAuditEvent event = new ExtractionAuditEvent();
        event.setId(String.valueOf(entity.getId()));
        event.setJobId(entity.getJobId());
        event.setSourceId(entity.getSourceId());
        event.setAction(entity.getAction());
        event.setUri(entity.getUri());
        event.setOutcome(entity.getOutcome());
        event.setDetail(entity.getDetail());
        event.setTimestamp(entity.getTimestamp());
        return event;
    }
}
