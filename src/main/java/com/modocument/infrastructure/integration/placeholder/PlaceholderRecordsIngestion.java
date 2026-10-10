package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.RecordsIngestion;
import org.springframework.stereotype.Component;

/** Until SE4's JSON-records ingestion lands. */
@Component
class PlaceholderRecordsIngestion implements RecordsIngestion {

    @Override
    public int createFromRecords(Command command) {
        throw new IllegalStateException("Records ingestion is not available yet.");
    }
}
