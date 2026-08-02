package com.modularbank.accounts.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.accounts.application.AccountsService;
import com.modularbank.accounts.infrastructure.messaging.events.TransferCompletedEvent;
import com.modularbank.accounts.infrastructure.messaging.events.TransferRequestedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEvent;
import com.modularbank.accounts.infrastructure.messaging.idempotency.ProcessedEventRepository;
import com.modularbank.accounts.infrastructure.outbox.OutboxEvent;
import com.modularbank.accounts.infrastructure.outbox.OutboxEventRepository;
import com.modularbank.accounts.shared.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.COMPLETED_ROUTING_KEY;

@Service
@RequiredArgsConstructor
public class TransferRequestedProcessor {

    private static final String AGGREGATE_TYPE = "Transfer";
    private static final String REQUESTED_EVENT_TYPE =
        "TransferRequested.v1";
    private static final String COMPLETED_EVENT_TYPE =
        "TransferCompleted.v1";

    private final AccountsService accountsService;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void process(TransferRequestedEvent event) {

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        accountsService.transfer(
            event.sourceAccountId(),
            event.targetAccountId(),
            Money.of(event.amount()),
            event.reference()
        );

        UUID completedEventId = UUID.randomUUID();

        TransferCompletedEvent completedEvent =
            new TransferCompletedEvent(
                completedEventId,
                event.transferId(),
                Instant.now(),
                TransferCompletedEvent.CURRENT_VERSION
            );

        OutboxEvent outboxEvent =
            OutboxEvent.pending(
                completedEventId,
                AGGREGATE_TYPE,
                event.transferId(),
                COMPLETED_EVENT_TYPE,
                COMPLETED_ROUTING_KEY,
                serialize(completedEvent)
            );

        outboxEventRepository.save(outboxEvent);

        processedEventRepository.save(
            ProcessedEvent.processed(
                event.eventId(),
                event.transferId(),
                REQUESTED_EVENT_TYPE
            )
        );
    }

    private String serialize(
        TransferCompletedEvent event
    ) {
        try {
            return objectMapper.writeValueAsString(event);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not serialize TransferCompletedEvent",
                exception
            );
        }
    }
}
