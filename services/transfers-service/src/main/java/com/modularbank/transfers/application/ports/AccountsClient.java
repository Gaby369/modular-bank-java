package com.modularbank.transfers.application.ports;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountsClient {

    boolean ownsAccount(
        UUID userId,
        UUID accountId
    );

    void transfer(
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String reference
    );
}