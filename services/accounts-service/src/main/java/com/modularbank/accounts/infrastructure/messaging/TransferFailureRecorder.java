package com.modularbank.accounts.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.accounts.infrastructure.messaging.events.TransferFailedEvent;
import com.modularbank.accounts.infrastructure.messaging.events.TransferRequestedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEventRepository;
import com.modularbank.accounts.infrastructure.outbox.OutboxEvent;
import com.modularbank.accounts.infrastructure.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.FAILED_ROUTING_KEY;

@Service
@RequiredArgsConstructor
public class TransferFailureRecorder {

    private static final String AGGREGATE_TYPE = "Transfer";
    private static final String REQUESTED_EVENT_TYPE =
        "TransferRequested.v1";
    private static final String FAILED_EVENT_TYPE =
        "TransferFailed.v1";

    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordFailure(
        TransferRequestedEvent requestedEvent,
        String reason
    ) {
        if (processedEventRepository.existsById(
            requestedEvent.eventId()
        )) {
            return;
        }

        UUID failedEventId = UUID.randomUUID();

        TransferFailedEvent failedEvent =
            new TransferFailedEvent(
                failedEventId,
                requestedEvent.transferId(),
                reason,
                Instant.now(),
                TransferFailedEvent.CURRENT_VERSION
            );

        outboxEventRepository.save(
            OutboxEvent.pending(
                failedEventId,
                AGGREGATE_TYPE,
                requestedEvent.transferId(),
                FAILED_EVENT_TYPE,
                FAILED_ROUTING_KEY,
                serialize(failedEvent)
            )
        );

        processedEventRepository.save(
            ProcessedEvent.processed(
                requestedEvent.eventId(),
                requestedEvent.transferId(),
                REQUESTED_EVENT_TYPE
            )
        );
    }

    private String serialize(
        TransferFailedEvent event
    ) {
        try {
            return objectMapper.writeValueAsString(event);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not serialize TransferFailedEvent",
                exception
            );
        }
    }
}
