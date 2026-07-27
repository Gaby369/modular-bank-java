package com.modularbank.accounts.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountSummary(UUID id, String accountNumber, BigDecimal balance) {}
