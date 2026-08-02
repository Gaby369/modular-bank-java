package com.modularbank.transfers.infrastructure.messaging;

import com.modularbank.transfers.domain.Transfer;
import com.modularbank.transfers.infrastructure.TransferRepository;
import com.modularbank.transfers.infrastructure.messaging.events.TransferCompletedEvent;
import com.modularbank.transfers.infrastructure.messaging.events.TransferFailedEvent;
import com.modularbank.transfers.infrastructure.messaging.idempotency.ProcessedEvent;
import com.modularbank.transfers.infrastructure.messaging.idempotency.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferResultProcessor {

    private static final String COMPLETED_EVENT_TYPE =
        "TransferCompleted.v1";

    private static final String FAILED_EVENT_TYPE =
        "TransferFailed.v1";

    private final TransferRepository transferRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void markCompleted(
        TransferCompletedEvent event
    ) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        Transfer transfer = transferRepository
            .findById(event.transferId())
            .orElseThrow(() -> new IllegalStateException(
                "Transfer not found: " + event.transferId()
            ));

        transfer.setStatus("COMPLETED");
        transferRepository.save(transfer);

        processedEventRepository.save(
            ProcessedEvent.processed(
                event.eventId(),
                event.transferId(),
                COMPLETED_EVENT_TYPE
            )
        );
    }

    @Transactional
    public void markFailed(
        TransferFailedEvent event
    ) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        Transfer transfer = transferRepository
            .findById(event.transferId())
            .orElseThrow(() -> new IllegalStateException(
                "Transfer not found: " + event.transferId()
            ));

        transfer.setStatus("FAILED");
        transferRepository.save(transfer);

        processedEventRepository.save(
            ProcessedEvent.processed(
                event.eventId(),
                event.transferId(),
                FAILED_EVENT_TYPE
            )
        );
    }
}
