package com.modularbank.accounts.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    schema = "accounts",
    name = "outbox_events"
)
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 150)
    private String routingKey;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OutboxEvent() {
    }

    private OutboxEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String routingKey,
        String correlationId,
        String payload
    ) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.correlationId = correlationId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent pending(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String routingKey,
        String payload
    ) {
        return pending(
            eventId,
            aggregateType,
            aggregateId,
            eventType,
            routingKey,
            null,
            payload
        );
    }

    public static OutboxEvent pending(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String routingKey,
        String correlationId,
        String payload
    ) {
        return new OutboxEvent(
            eventId,
            aggregateType,
            aggregateId,
            eventType,
            routingKey,
            correlationId,
            payload
        );
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void registerFailure(
        String error,
        int maximumAttempts
    ) {
        this.attempts++;
        this.lastError = error;

        if (this.attempts >= maximumAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
