package com.modularbank.accounts.api;

import com.modularbank.accounts.application.AccountsService;
import com.modularbank.accounts.application.dto.AccountSummary;
import com.modularbank.accounts.shared.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountsController {

    private final AccountsService accountsService;

    @GetMapping
    public List<AccountSummary> getAccounts(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return accountsService.findByOwner(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountSummary createAccount(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return accountsService.createAccount(userId);
    }

    @GetMapping("/{id}/balance")
    public Map<String, String> getBalance(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        List<AccountSummary> owned = accountsService.findByOwner(userId);
        boolean isOwner = owned.stream().anyMatch(a -> a.id().equals(id));
        if (!isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        Money balance = accountsService.getBalance(id);
        return Map.of("amount", balance.amount().toPlainString());
    }
}
