package com.modularbank.transfers.infrastructure;

import com.modularbank.transfers.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransferRepository
    extends JpaRepository<Transfer, UUID> {

    List<Transfer>
    findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(
        UUID sourceAccountId,
        UUID targetAccountId
    );
}