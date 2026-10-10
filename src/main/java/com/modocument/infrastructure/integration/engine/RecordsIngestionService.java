package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.application.usecase.IngestionJobUseCase;
import org.springframework.stereotype.Component;

/**
 * {@link RecordsIngestion} (ws25): JSON records become an IngestionJob that
 * skips parsing and runs map → validate against a bank-shared template
 * ({@link IngestionJobUseCase#createFromRecords}). Callers: inbound flows
 * and SE2's {@code POST /api/integration/v1/records}. Runs synchronously in
 * the caller's thread and tenant; the returned job is already VALIDATED (or
 * FAILED, with its error on the job).
 */
@Component
public class RecordsIngestionService implements RecordsIngestion {

    private final IngestionJobUseCase ingestion;

    public RecordsIngestionService(IngestionJobUseCase ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public int createFromRecords(Command command) {
        return ingestion.createFromRecords(command.bankId(), command.ownerUserId(),
                command.templateId(), command.externalBatchId(), command.records(),
                command.origin()).getIngestionJobId();
    }
}
