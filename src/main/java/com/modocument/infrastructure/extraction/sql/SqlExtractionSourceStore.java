package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.DbSourceConfig;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionSourceEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQL-backed extraction sources, one row per source, owned by an AppUser. */
@Component
@ConditionalOnProperty(name = "extraction.store", havingValue = "sql")
public class SqlExtractionSourceStore implements ExtractionSourceStore {

    private final ExtractionSourceJpaRepository repository;

    public SqlExtractionSourceStore(ExtractionSourceJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ExtractionSource save(ExtractionSource source) {
        if (source.getId() == null) {
            source.setCreatedDate(Instant.now());
        }
        source.setUpdatedDate(Instant.now());
        ExtractionSourceEntity saved = repository.save(toEntity(source));
        source.setId(String.valueOf(saved.getId()));
        return source;
    }

    @Override
    public Optional<ExtractionSource> getById(String id) {
        Long entityId = JsonColumns.parseId(id);
        if (entityId == null) {
            return Optional.empty();
        }
        return repository.findById(entityId).map(SqlExtractionSourceStore::toDomain);
    }

    @Override
    public Optional<ExtractionSource> getByIdAndOwner(String id, int ownerUserId) {
        Long entityId = JsonColumns.parseId(id);
        if (entityId == null) {
            return Optional.empty();
        }
        return repository.findByIdAndOwnerUserId(entityId, ownerUserId)
                .map(SqlExtractionSourceStore::toDomain);
    }

    @Override
    public List<ExtractionSource> list() {
        return repository.findAllByOrderByIdDesc().stream()
                .map(SqlExtractionSourceStore::toDomain).toList();
    }

    @Override
    public List<ExtractionSource> listByOwner(int ownerUserId) {
        return repository.findByOwnerUserIdOrderByIdDesc(ownerUserId).stream()
                .map(SqlExtractionSourceStore::toDomain).toList();
    }

    @Override
    public void delete(String id) {
        Long entityId = JsonColumns.parseId(id);
        if (entityId != null) {
            repository.deleteById(entityId);
        }
    }

    private static ExtractionSourceEntity toEntity(ExtractionSource source) {
        ExtractionSourceEntity entity = new ExtractionSourceEntity();
        entity.setId(JsonColumns.parseId(source.getId()));
        entity.setOwnerUserId(source.getOwnerUserId());
        entity.setName(source.getName());
        entity.setType(source.getType());
        entity.setTermsAcknowledged(source.isTermsAcknowledged());
        entity.setDataSpec(source.getDataSpec());
        entity.setTemplateId(source.getTemplateId());
        entity.setWebConfigJson(JsonColumns.toJson(source.getWeb()));
        entity.setApiConfigJson(JsonColumns.toJson(source.getApi()));
        entity.setDbConfigJson(JsonColumns.toJson(source.getDb()));
        entity.setCreatedDate(source.getCreatedDate());
        entity.setUpdatedDate(source.getUpdatedDate());
        return entity;
    }

    private static ExtractionSource toDomain(ExtractionSourceEntity entity) {
        ExtractionSource source = new ExtractionSource();
        source.setId(String.valueOf(entity.getId()));
        source.setOwnerUserId(entity.getOwnerUserId());
        source.setName(entity.getName());
        source.setType(entity.getType());
        source.setTermsAcknowledged(entity.isTermsAcknowledged());
        source.setDataSpec(entity.getDataSpec());
        source.setTemplateId(entity.getTemplateId());
        source.setWeb(JsonColumns.fromJson(entity.getWebConfigJson(), WebSourceConfig.class));
        source.setApi(JsonColumns.fromJson(entity.getApiConfigJson(), ApiSourceConfig.class));
        source.setDb(JsonColumns.fromJson(entity.getDbConfigJson(), DbSourceConfig.class));
        source.setCreatedDate(entity.getCreatedDate());
        source.setUpdatedDate(entity.getUpdatedDate());
        return source;
    }
}
