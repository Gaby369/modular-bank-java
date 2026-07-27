package com.modularbank.accounts.api.internal;

import com.modularbank.accounts.api.internal.dto.AccountOperationRequest;
import com.modularbank.accounts.api.internal.dto.InternalCreateAccountRequest;
import com.modularbank.accounts.api.internal.dto.InternalTransferRequest;
import com.modularbank.accounts.application.AccountsService;
import com.modularbank.accounts.application.dto.AccountSummary;
import com.modularbank.accounts.shared.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalAccountsController {

    private final AccountsService accountsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountSummary createAccount(
        @RequestBody InternalCreateAccountRequest request
    ) {
        return accountsService.createAccount(request.userId());
    }

    @GetMapping("/owner/{userId}")
    public List<AccountSummary> findByOwner(
        @PathVariable UUID userId
    ) {
        return accountsService.findByOwner(userId);
    }

    @GetMapping("/{accountId}/balance")
    public Map<String, String> getBalance(
        @PathVariable UUID accountId
    ) {
        Money balance = accountsService.getBalance(accountId);

        return Map.of(
            "amount",
            balance.amount().toPlainString()
        );
    }

    @PostMapping("/{accountId}/debit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void debit(
        @PathVariable UUID accountId,
        @RequestBody AccountOperationRequest request
    ) {
        accountsService.debit(
            accountId,
            Money.of(request.amount()),
            request.reference()
        );
    }

    @PostMapping("/{accountId}/credit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void credit(
        @PathVariable UUID accountId,
        @RequestBody AccountOperationRequest request
    ) {
        accountsService.credit(
            accountId,
            Money.of(request.amount()),
            request.reference()
        );
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(
        @RequestBody InternalTransferRequest request
    ) {
        accountsService.transfer(
            request.sourceAccountId(),
            request.targetAccountId(),
            Money.of(request.amount()),
            request.reference()
        );
    }
}