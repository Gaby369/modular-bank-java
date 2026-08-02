package com.modularbank.transfers.infrastructure.messaging;

import com.modularbank.transfers.domain.Transfer;
import com.modularbank.transfers.infrastructure.TransferRepository;
import com.modularbank.transfers.infrastructure.messaging.events.TransferCompletedEvent;
import com.modularbank.transfers.infrastructure.messaging.events.TransferFailedEvent;
import com.modularbank.transfers.infrastructure.messaging.idempotency.ProcessedEvent;
import com.modularbank.transfers.infrastructure.messaging.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferResultProcessorTest {

    private final TransferRepository transferRepository =
        mock(TransferRepository.class);

    private final ProcessedEventRepository processedEventRepository =
        mock(ProcessedEventRepository.class);

    private final TransferResultProcessor processor =
        new TransferResultProcessor(
            transferRepository,
            processedEventRepository
        );

    @Test
    void shouldMarkTransferAsCompletedAndRecordEvent() {
        UUID eventId = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

        UUID transferId = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

        TransferCompletedEvent event =
            new TransferCompletedEvent(
                eventId,
                transferId,
                Instant.parse("2026-08-01T20:00:00Z"),
                TransferCompletedEvent.CURRENT_VERSION
            );

        Transfer transfer = pendingTransfer(transferId);

        when(processedEventRepository.existsById(eventId))
            .thenReturn(false);

        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(transfer));

        processor.markCompleted(event);

        assertEquals(
            "COMPLETED",
            transfer.getStatus()
        );

        verify(transferRepository).save(transfer);

        ArgumentCaptor<ProcessedEvent> processedCaptor =
            ArgumentCaptor.forClass(ProcessedEvent.class);

        verify(processedEventRepository)
            .save(processedCaptor.capture());

        ProcessedEvent processedEvent =
            processedCaptor.getValue();

        assertEquals(eventId, processedEvent.getEventId());
        assertEquals(transferId, processedEvent.getTransferId());
        assertEquals(
            "TransferCompleted.v1",
            processedEvent.getEventType()
        );
    }

    @Test
    void shouldMarkTransferAsFailedAndRecordEvent() {
        UUID eventId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID transferId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        TransferFailedEvent event =
            new TransferFailedEvent(
                eventId,
                transferId,
                "Insufficient funds",
                Instant.parse("2026-08-01T20:05:00Z"),
                TransferFailedEvent.CURRENT_VERSION
            );

        Transfer transfer = pendingTransfer(transferId);

        when(processedEventRepository.existsById(eventId))
            .thenReturn(false);

        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(transfer));

        processor.markFailed(event);

        assertEquals(
            "FAILED",
            transfer.getStatus()
        );

        verify(transferRepository).save(transfer);

        ArgumentCaptor<ProcessedEvent> processedCaptor =
            ArgumentCaptor.forClass(ProcessedEvent.class);

        verify(processedEventRepository)
            .save(processedCaptor.capture());

        ProcessedEvent processedEvent =
            processedCaptor.getValue();

        assertEquals(eventId, processedEvent.getEventId());
        assertEquals(transferId, processedEvent.getTransferId());
        assertEquals(
            "TransferFailed.v1",
            processedEvent.getEventType()
        );
    }

    @Test
    void shouldIgnoreAlreadyProcessedResultEvent() {
        UUID eventId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        UUID transferId = UUID.fromString(
            "66666666-6666-6666-6666-666666666666"
        );

        TransferCompletedEvent event =
            new TransferCompletedEvent(
                eventId,
                transferId,
                Instant.parse("2026-08-01T20:10:00Z"),
                TransferCompletedEvent.CURRENT_VERSION
            );

        when(processedEventRepository.existsById(eventId))
            .thenReturn(true);

        processor.markCompleted(event);

        verify(processedEventRepository)
            .existsById(eventId);

        verify(
            processedEventRepository,
            never()
        ).save(any(ProcessedEvent.class));

        verifyNoInteractions(transferRepository);
    }

    private Transfer pendingTransfer(UUID transferId) {
        return Transfer.builder()
            .id(transferId)
            .sourceAccountId(
                UUID.fromString(
                    "77777777-7777-7777-7777-777777777777"
                )
            )
            .targetAccountId(
                UUID.fromString(
                    "88888888-8888-8888-8888-888888888888"
                )
            )
            .amount(new BigDecimal("25.00"))
            .reference("processor-unit-test")
            .status("PENDING")
            .build();
    }
}
