package com.modularbank.transfers.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.transfers.application.dto.TransferRequest;
import com.modularbank.transfers.application.ports.AccountsClient;
import com.modularbank.transfers.domain.Transfer;
import com.modularbank.transfers.infrastructure.TransferRepository;
import com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants;
import com.modularbank.transfers.infrastructure.messaging.events.TransferRequestedEvent;
import com.modularbank.transfers.infrastructure.outbox.OutboxEvent;
import com.modularbank.transfers.infrastructure.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferUseCase {

    private static final String AGGREGATE_TYPE = "Transfer";
    private static final String EVENT_TYPE = "TransferRequested.v1";
    private static final String CORRELATION_ID_KEY =
        "correlationId";

    private final TransferRepository transferRepository;
    private final AccountsClient accountsClient;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Transfer execute(
        UUID userId,
        TransferRequest request
    ) {
        if (
            request.sourceAccountId()
                .equals(request.targetAccountId())
        ) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Source and target accounts must be different"
            );
        }

        boolean ownsSourceAccount =
            accountsClient.ownsAccount(
                userId,
                request.sourceAccountId()
            );

        if (!ownsSourceAccount) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Source account does not belong to the authenticated user"
            );
        }

        Transfer transfer = Transfer.builder()
            .sourceAccountId(request.sourceAccountId())
            .targetAccountId(request.targetAccountId())
            .amount(request.amount())
            .reference(request.reference())
            .status("PENDING")
            .build();

        transfer = transferRepository.save(transfer);

        UUID eventId = UUID.randomUUID();

        TransferRequestedEvent event =
            new TransferRequestedEvent(
                eventId,
                transfer.getId(),
                userId,
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amount(),
                request.reference(),
                Instant.now(),
                TransferRequestedEvent.CURRENT_VERSION
            );

        OutboxEvent outboxEvent =
            OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                transfer.getId(),
                EVENT_TYPE,
                TransferMessagingConstants.REQUESTED_ROUTING_KEY,
                MDC.get(CORRELATION_ID_KEY),
                serializeEvent(event)
            );

        outboxEventRepository.save(outboxEvent);

        return transfer;
    }

    @Transactional(readOnly = true)
    public List<Transfer> getHistory(
        UUID userId,
        UUID accountId
    ) {
        boolean ownsAccount =
            accountsClient.ownsAccount(
                userId,
                accountId
            );

        if (!ownsAccount) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Account does not belong to the authenticated user"
            );
        }

        return transferRepository
            .findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(
                accountId,
                accountId
            );
    }

    private String serializeEvent(
        TransferRequestedEvent event
    ) {
        try {
            return objectMapper.writeValueAsString(event);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not serialize TransferRequestedEvent",
                exception
            );
        }
    }
}
