package com.modocument.infrastructure.integration.placeholder;

import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.domain.model.integration.FlowEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Until SE4's event log lands: drops events, pages nothing. */
@Component
class PlaceholderIntegrationEventLog implements IntegrationEventLog {

    @Override
    public void publish(int bankId, FlowEvent type, String entityType, String entityId,
                        Map<String, Object> data) {
        // no subscribers yet
    }

    @Override
    public List<Event> page(int bankId, long afterId, int limit) {
        return List.of();
    }
}
