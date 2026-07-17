package com.modocument.application.dto;

import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.document.RawRecord;

import java.util.List;
import java.util.Map;

/**
 * What executing one extraction task produced: fetched items (raw payload +
 * its normalized rows), follow-up tasks discovered (links, next pages, next
 * batches), and incremental-state updates to merge into the ConnectorState.
 */
public record ConnectorResult(List<FetchedItem> items,
                              List<ExtractionTask> followUps,
                              Map<String, String> stateUpdates) {

    /** A raw payload with the flat rows normalized out of it. */
    public record FetchedItem(RawRecord record, List<Map<String, String>> rows) {
    }

    public static ConnectorResult empty() {
        return new ConnectorResult(List.of(), List.of(), Map.of());
    }
}
