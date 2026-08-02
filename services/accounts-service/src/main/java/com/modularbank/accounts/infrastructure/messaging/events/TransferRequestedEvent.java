package com.modularbank.accounts.infrastructure.messaging.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferRequestedEvent(
        UUID eventId,
        UUID transferId,
        UUID userId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String reference,
        Instant occurredAt,
        String version
) {
    public static final String CURRENT_VERSION = "1.0";
}
