package com.modocument.infrastructure.extraction.connector;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.domain.document.DbSourceConfig;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.ExtractionProperties;
import com.modocument.infrastructure.extraction.InProcessJobSecrets;
import com.modocument.infrastructure.extraction.worker.NodeIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JdbcSourceConnectorTest {

    private static final String URL = "jdbc:h2:mem:extraction-test;DB_CLOSE_DELAY=-1";

    private final InProcessJobSecrets secrets = new InProcessJobSecrets();
    private final NodeIdentity node = new NodeIdentity();
    private final JdbcSourceConnector connector = new JdbcSourceConnector(
            secrets, node, new ExtractionProperties(), mock(ExtractionAuditLog.class));

    @BeforeEach
    void seedDatabase() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.h2.Driver(), URL, "sa", ""));
        jdbc.execute("DROP TABLE IF EXISTS \"People\"");
        jdbc.execute("CREATE TABLE \"People\" (\"Id\" INT PRIMARY KEY, \"Name\" VARCHAR(50))");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO \"People\" VALUES (?, ?)", i, "Person" + i);
        }
    }

    private ExtractionSource source(String table, String watermark, int batchSize) {
        DbSourceConfig config = new DbSourceConfig();
        config.setJdbcUrl(URL);
        config.setTable(table);
        config.setWatermarkColumn(watermark);
        config.setBatchSize(batchSize);
        ExtractionSource source = new ExtractionSource();
        source.setId("s1");
        source.setType(SourceType.DATABASE);
        source.setDb(config);
        return source;
    }

    private ExtractionJob job() {
        ExtractionJob job = new ExtractionJob();
        job.setId("j1");
        job.setSourceId("s1");
        secrets.put("j1", Map.of("dbUsername", "sa", "dbPassword", ""));
        return job;
    }

    @Test
    void seedTaskIsNodeAffine() {
        List<ExtractionTask> seeds = connector.seedTasks(job(), source("People", "Id", 2), null);

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).getNodeAffinity()).isEqualTo(node.id());
    }

    @Test
    void watermarkBatchesChainUntilTableIsDrained() throws Exception {
        ExtractionJob job = job();
        ExtractionSource source = source("People", "Id", 2);

        ExtractionTask task = connector.seedTasks(job, source, null).get(0);
        int totalRows = 0;
        int batches = 0;
        while (task != null) {
            ConnectorResult result = connector.execute(task, job, source, null);
            totalRows += result.items().get(0).rows().size();
            batches++;
            task = result.followUps().isEmpty() ? null : result.followUps().get(0);
        }

        assertThat(totalRows).isEqualTo(5);
        assertThat(batches).isEqualTo(3); // 2 + 2 + 1
    }

    @Test
    void watermarkStateEnablesIncrementalRuns() throws Exception {
        ExtractionJob job = job();
        ExtractionSource source = source("People", "Id", 10);

        ConnectorResult result = connector.execute(
                connector.seedTasks(job, source, null).get(0), job, source, null);

        assertThat(result.stateUpdates()).containsEntry("watermark", "5");
        assertThat(result.items().get(0).rows().get(0))
                .containsEntry("Id", "1").containsEntry("Name", "Person1");
    }

    @Test
    void invalidIdentifiersAreRejectedBeforeAnyQuery() {
        ExtractionJob job = job();
        ExtractionSource source = source("People; DROP TABLE People", null, 10);
        ExtractionTask task = connector.seedTasks(job, source, null).get(0);

        assertThatThrownBy(() -> connector.execute(task, job, source, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid table");
    }
}
