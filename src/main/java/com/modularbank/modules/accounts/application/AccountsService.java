package com.modularbank.modules.accounts.application;

import com.modularbank.modules.accounts.application.dto.AccountSummary;
import com.modularbank.shared.domain.Money;

import java.util.List;
import java.util.UUID;

public interface AccountsService {

    AccountSummary createAccount(UUID userId);

    Money getBalance(UUID accountId);

    void debit(
        UUID accountId,
        Money amount,
        String reference
    );

    void credit(
        UUID accountId,
        Money amount,
        String reference
    );

    void transfer(
        UUID sourceAccountId,
        UUID targetAccountId,
        Money amount,
        String reference
    );

    List<AccountSummary> findByOwner(UUID userId);
}