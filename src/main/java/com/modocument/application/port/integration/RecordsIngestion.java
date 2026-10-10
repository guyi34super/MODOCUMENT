package com.modocument.application.port.integration;

import java.util.List;
import java.util.Map;

/**
 * JSON-records ingestion (ws25, SE4 implements; SE2's {@code POST
 * /api/integration/v1/records} and SE4's inbound flows call it). Creates an
 * IngestionJob that skips parsing — the records ARE the table — and runs the
 * usual map → validate steps against the template.
 */
public interface RecordsIngestion {

    /** @return the new ingestion job id */
    int createFromRecords(Command command);

    /**
     * @param ownerUserId the job owner: the user who created the API client or
     *                    flow (jobs stay owner-scoped)
     * @param origin      e.g. {@code "api-client:12"}, {@code "flow:5/run:77"};
     *                    stored on the job and in its audit trail
     */
    record Command(int bankId, int ownerUserId, int templateId, String externalBatchId,
                   List<Map<String, Object>> records, String origin) {
        public Command {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}
