package com.modularbank.accounts.api.internal.dto;

import java.math.BigDecimal;

public record AccountOperationRequest(
    BigDecimal amount,
    String reference
) {
}