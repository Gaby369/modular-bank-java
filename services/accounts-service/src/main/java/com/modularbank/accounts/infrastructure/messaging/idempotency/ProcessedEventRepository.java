package com.modularbank.accounts.infrastructure.messaging.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository
    extends JpaRepository<ProcessedEvent, UUID> {
}
