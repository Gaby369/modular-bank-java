package com.modularbank.accounts.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modularbank.accounts.infrastructure.messaging.events.TransferRequestedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEventRepository;
import com.modularbank.accounts.infrastructure.outbox.OutboxEvent;
import com.modularbank.accounts.infrastructure.outbox.OutboxEventRepository;
import com.modularbank.accounts.infrastructure.outbox.OutboxStatus;
import com.modularbank.accounts.shared.observability.TraceContextStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferFailureRecorderTest {

    private final ProcessedEventRepository processedEventRepository =
        mock(ProcessedEventRepository.class);

    private final OutboxEventRepository outboxEventRepository =
        mock(OutboxEventRepository.class);

    private final ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final TraceContextStore traceContextStore =
        mock(TraceContextStore.class);

    private final TransferFailureRecorder failureRecorder =
        new TransferFailureRecorder(
            processedEventRepository,
            outboxEventRepository,
            objectMapper,
            traceContextStore
        );

    @BeforeEach
    void setUpTraceContext() {
        when(traceContextStore.capture()).thenReturn(
            new TraceContextStore.StoredTraceContext(
                null,
                null
            )
        );
    }

    @Test
    void shouldCreateFailedEventAndMarkRequestAsProcessed() {
        TransferRequestedEvent requestedEvent =
            requestedEvent();

        when(
            processedEventRepository.existsById(
                requestedEvent.eventId()
            )
        ).thenReturn(false);

        failureRecorder.recordFailure(
            requestedEvent,
            "Insufficient funds"
        );

        ArgumentCaptor<OutboxEvent> outboxCaptor =
            ArgumentCaptor.forClass(OutboxEvent.class);

        verify(outboxEventRepository)
            .save(outboxCaptor.capture());

        OutboxEvent savedOutbox =
            outboxCaptor.getValue();

        assertEquals(
            requestedEvent.transferId(),
            savedOutbox.getAggregateId()
        );

        assertEquals(
            "TransferFailed.v1",
            savedOutbox.getEventType()
        );

        assertEquals(
            "transfer.failed.v1",
            savedOutbox.getRoutingKey()
        );

        assertEquals(
            OutboxStatus.PENDING,
            savedOutbox.getStatus()
        );

        assertTrue(
            savedOutbox
                .getPayload()
                .contains("Insufficient funds")
        );

        assertTrue(
            savedOutbox
                .getPayload()
                .contains(
                    requestedEvent
                        .transferId()
                        .toString()
                )
        );

        verify(processedEventRepository)
            .save(any(ProcessedEvent.class));
    }

    @Test
    void shouldIgnoreFailureForAlreadyProcessedEvent() {
        TransferRequestedEvent requestedEvent =
            requestedEvent();

        when(
            processedEventRepository.existsById(
                requestedEvent.eventId()
            )
        ).thenReturn(true);

        failureRecorder.recordFailure(
            requestedEvent,
            "Insufficient funds"
        );

        verify(processedEventRepository)
            .existsById(requestedEvent.eventId());

        verify(
            processedEventRepository,
            never()
        ).save(any(ProcessedEvent.class));

        verifyNoInteractions(outboxEventRepository);
    }

    private TransferRequestedEvent requestedEvent() {
        return new TransferRequestedEvent(
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            ),
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            ),
            UUID.fromString(
                "33333333-3333-3333-3333-333333333333"
            ),
            UUID.fromString(
                "44444444-4444-4444-4444-444444444444"
            ),
            UUID.fromString(
                "55555555-5555-5555-5555-555555555555"
            ),
            new BigDecimal("25.00"),
            "failed-automated-test",
            Instant.parse("2026-08-01T20:00:00Z"),
            TransferRequestedEvent.CURRENT_VERSION
        );
    }
}
