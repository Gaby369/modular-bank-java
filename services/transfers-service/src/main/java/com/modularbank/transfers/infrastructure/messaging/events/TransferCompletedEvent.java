package com.modularbank.transfers.infrastructure.messaging.events;

import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        Instant occurredAt,
        String version
) {
    public static final String CURRENT_VERSION = "1.0";
}
