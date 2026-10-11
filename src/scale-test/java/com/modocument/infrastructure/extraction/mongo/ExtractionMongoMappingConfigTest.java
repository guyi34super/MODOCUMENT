package com.modocument.infrastructure.extraction.mongo;

import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.data.mongodb.MongoManagedTypes;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws29 (scale profile only — src/scale-test/java): the extraction documents
 * lost their {@code @Document} annotations when Mongo moved out of the
 * default build; the scale mapping context must map them to EXACTLY the
 * collections existing scale-mode databases already use.
 * Run: {@code mvn -B -Pscale -Dtest=ExtractionMongoMappingConfigTest test}.
 */
class ExtractionMongoMappingConfigTest {

    @Test
    void collectionNamesMatchTheFormerDocumentAnnotations() {
        MongoMappingContext context = context();

        assertThat(collection(context, ExtractionTask.class)).isEqualTo("extractionTasks");
        assertThat(collection(context, ConnectorState.class)).isEqualTo("connectorState");
        assertThat(collection(context, RawRecord.class)).isEqualTo("rawRecords");
        assertThat(collection(context, ExtractionAuditEvent.class)).isEqualTo("extractionAudit");
        assertThat(collection(context, ProcessedRecord.class)).isEqualTo("processedRecords");
        assertThat(collection(context, ExtractionSource.class)).isEqualTo("extractionSources");
        assertThat(collection(context, ExtractionJob.class)).isEqualTo("extractionJobs");
    }

    @Test
    void otherTypesKeepSpringDataDefaults() {
        MongoMappingContext context = context();
        assertThat(collection(context, Unmapped.class)).isEqualTo("unmapped");
    }

    /** Through the real bean method, with Boot's default conversions (simple types). */
    private static MongoMappingContext context() {
        MongoMappingContext context = new ExtractionMongoMappingConfig().mongoMappingContext(
                new MongoProperties(), new MongoCustomConversions(List.of()), MongoManagedTypes.empty());
        context.afterPropertiesSet();
        return context;
    }

    private static String collection(MongoMappingContext context, Class<?> type) {
        return context.getRequiredPersistentEntity(type).getCollection();
    }

    static class Unmapped {
        String id;
    }
}
