package com.modocument.application.port;

import com.modocument.application.dto.ConnectorResult;
import com.modocument.domain.document.ConnectorState;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionTask;
import com.modocument.domain.model.SourceType;

import java.util.List;

/**
 * A source-type-specific extractor. seedTasks plans the initial work for a
 * job; execute performs one claimed task and reports fetched items, follow-up
 * tasks it discovered, and incremental-state updates.
 */
public interface Connector {

    SourceType type();

    List<ExtractionTask> seedTasks(ExtractionJob job, ExtractionSource source, ConnectorState state);

    ConnectorResult execute(ExtractionTask task, ExtractionJob job, ExtractionSource source,
                            ConnectorState state) throws Exception;
}
