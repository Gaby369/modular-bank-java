package com.modularbank.accounts.infrastructure.messaging.events;

import java.time.Instant;
import java.util.UUID;

public record TransferFailedEvent(
        UUID eventId,
        UUID transferId,
        String reason,
        Instant occurredAt,
        String version
) {
    public static final String CURRENT_VERSION = "1.0";
}
