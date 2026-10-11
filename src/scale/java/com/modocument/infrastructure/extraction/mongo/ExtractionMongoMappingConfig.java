package com.modocument.infrastructure.extraction.mongo;

import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mapping.model.FieldNamingStrategy;
import org.springframework.data.mongodb.MongoManagedTypes;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.BasicMongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.util.TypeInformation;

import java.util.Map;

/**
 * Collection names of the extraction documents (ws29). The domain classes
 * carry no MongoDB annotations any more — the default build has no Mongo
 * jars — so this scale-only mapping context restores EXACTLY the names the
 * former {@code @Document(collection = ...)} annotations declared; existing
 * scale-mode databases keep their data. (The former {@code @Indexed} field
 * annotations were inert: auto index creation is off; the indexes the
 * pipeline relies on are {@link MongoIndexInitializer}'s.)
 *
 * <p>Same settings as Boot's own {@code MongoDataConfiguration} mapping
 * context (managed types, auto-index switch, field naming strategy, simple
 * types), which backs off for this bean.
 */
@Configuration
@ConditionalOnProperty(name = "extraction.store", havingValue = "mongo")
public class ExtractionMongoMappingConfig {

    static final Map<Class<?>, String> COLLECTIONS = Map.of(
            ExtractionTask.class, "extractionTasks",
            ConnectorState.class, "connectorState",
            RawRecord.class, "rawRecords",
            ExtractionAuditEvent.class, "extractionAudit",
            ProcessedRecord.class, "processedRecords",
            ExtractionSource.class, "extractionSources",
            ExtractionJob.class, "extractionJobs");

    @Bean
    public MongoMappingContext mongoMappingContext(MongoProperties properties,
                                                   MongoCustomConversions conversions,
                                                   MongoManagedTypes managedTypes) {
        MongoMappingContext context = new ExtractionMappingContext();
        if (properties.isAutoIndexCreation() != null) {
            context.setAutoIndexCreation(properties.isAutoIndexCreation());
        }
        context.setManagedTypes(managedTypes);
        Class<?> strategyClass = properties.getFieldNamingStrategy();
        if (strategyClass != null) {
            context.setFieldNamingStrategy((FieldNamingStrategy) BeanUtils.instantiateClass(strategyClass));
        }
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        return context;
    }

    static final class ExtractionMappingContext extends MongoMappingContext {
        @Override
        protected <T> BasicMongoPersistentEntity<T> createPersistentEntity(TypeInformation<T> typeInformation) {
            String collection = COLLECTIONS.get(typeInformation.getType());
            if (collection == null) {
                return super.createPersistentEntity(typeInformation);
            }
            return new BasicMongoPersistentEntity<>(typeInformation) {
                @Override
                public String getCollection() {
                    return collection;
                }
            };
        }
    }
}
