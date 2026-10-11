package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ws28: one dispatch round loads each flow and connection once, however many
 * of its deliveries it sends (it used to reload both per delivery).
 */
class OutboundDispatcherTickCacheTest {

    private static final int BANK = 3;
    private static final long RUN = 11L;

    @Test
    void aRoundOfDeliveriesOfOneFlowLoadsTheFlowAndConnectionOnce() {
        OutboundDeliveryJpaRepository deliveries = mock(OutboundDeliveryJpaRepository.class);
        IntegrationFlowJpaRepository flows = mock(IntegrationFlowJpaRepository.class);
        IntegrationConnectionJpaRepository connections = mock(IntegrationConnectionJpaRepository.class);
        IntegrationRuns runs = mock(IntegrationRuns.class);

        IntegrationFlow flow = new IntegrationFlow();
        flow.setId(5L);
        flow.setBankId(BANK);
        flow.setConnectionId(9L);
        IntegrationConnection connection = new IntegrationConnection();
        connection.setId(9L);
        connection.setBankId(BANK);
        connection.setMode(ConnectionMode.AGENT);
        when(flows.findByIdAndBankId(5L, BANK)).thenReturn(Optional.of(flow));
        when(connections.findByIdAndBankId(9L, BANK)).thenReturn(Optional.of(connection));

        List<OutboundDelivery> pending = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            OutboundDelivery delivery = new OutboundDelivery();
            delivery.setId(id);
            delivery.setBankId(BANK);
            delivery.setFlowId(5L);
            delivery.setRunId(RUN);
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setPayloadKind(PayloadKind.VALIDATED_RECORDS);
            pending.add(delivery);
            when(deliveries.findByIdAndBankId(id, BANK)).thenReturn(Optional.of(delivery));
            when(deliveries.findById(id)).thenReturn(Optional.of(delivery));
        }
        when(deliveries.findByBankIdAndRunIdAndStatusOrderByIdAsc(BANK, RUN, DeliveryStatus.PENDING))
                .thenReturn(pending);
        when(deliveries.countByBankIdAndRunIdAndStatus(anyInt(), anyLong(), any())).thenReturn(0L);
        when(runs.find(anyInt(), anyLong())).thenReturn(Optional.empty());

        OutboundDispatcher dispatcher = new OutboundDispatcher(deliveries, flows, connections,
                mock(IntegrationConnectors.class), mock(PayloadBuilder.class), runs,
                mock(IntegrationSettings.class), mock(AuditRecorder.class), new IntegrationEngineProperties(),
                mock(JdbcTemplate.class), new NoOpTransactions());

        dispatcher.dispatchRun(BANK, RUN);

        verify(flows, times(1)).findByIdAndBankId(5L, BANK);
        verify(connections, times(1)).findByIdAndBankId(9L, BANK);
        // Every delivery was still handled (parked for the agent).
        verify(runs, times(4)).update(eq(BANK), eq(RUN), any());
    }

    private static final class NoOpTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
