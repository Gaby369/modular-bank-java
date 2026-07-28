package com.modularbank.accounts.infrastructure.messaging.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    schema = "accounts",
    name = "processed_events"
)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    private ProcessedEvent(
        UUID eventId,
        UUID transferId,
        String eventType
    ) {
        this.eventId = eventId;
        this.transferId = transferId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public static ProcessedEvent processed(
        UUID eventId,
        UUID transferId,
        String eventType
    ) {
        return new ProcessedEvent(
            eventId,
            transferId,
            eventType
        );
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
