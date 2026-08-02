package com.modularbank.accounts.api.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InternalTransferRequest(
    UUID sourceAccountId,
    UUID targetAccountId,
    BigDecimal amount,
    String reference
) {
}