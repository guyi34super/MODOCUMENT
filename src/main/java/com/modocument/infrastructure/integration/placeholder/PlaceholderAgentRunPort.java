package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.OutboundBatch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Until SE4's flow engine lands: no tasks, every run id is unknown. */
@Component
class PlaceholderAgentRunPort implements AgentRunPort {

    @Override
    public List<AgentTask> claimTasks(int bankId, int agentId, int max) {
        return List.of();
    }

    @Override
    public void acceptRecords(int bankId, int agentId, long runId, List<Map<String, Object>> records,
                              String nextCursor, boolean last) {
        throw new NoSuchElementException("run " + runId);
    }

    @Override
    public OutboundBatch payload(int bankId, int agentId, long runId) {
        throw new NoSuchElementException("run " + runId);
    }

    @Override
    public void complete(int bankId, int agentId, long runId, AgentRunResult result) {
        throw new NoSuchElementException("run " + runId);
    }
}
