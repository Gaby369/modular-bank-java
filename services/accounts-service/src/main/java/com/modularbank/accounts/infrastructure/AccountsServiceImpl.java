package com.modularbank.accounts.infrastructure;

import com.modularbank.accounts.application.AccountsService;
import com.modularbank.accounts.application.dto.AccountSummary;
import com.modularbank.accounts.domain.Account;
import com.modularbank.accounts.shared.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountsServiceImpl implements AccountsService {

    private final AccountRepository accountRepository;

    @Override
    @Transactional
    public AccountSummary createAccount(UUID userId) {
        Account account = Account.builder()
            .userId(userId)
            .accountNumber(generateAccountNumber())
            .balance(BigDecimal.ZERO.setScale(4))
            .build();
        Account saved = accountRepository.save(account);
        return toSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Money getBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        return Money.of(account.getBalance());
    }

    @Override
    @Transactional
    public void debit(UUID accountId, Money amount, String reference) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        int updated = accountRepository.debitIfSufficient(accountId, amount.amount());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
        }
    }

    @Override
    @Transactional
    public void credit(UUID accountId, Money amount, String reference) {
        int updated = accountRepository.credit(accountId, amount.amount());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
    }

@Override
@Transactional
public void transfer(
    UUID sourceAccountId,
    UUID targetAccountId,
    Money amount,
    String reference
) {
    if (sourceAccountId.equals(targetAccountId)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Source and target accounts must be different"
        );
    }

    if (!accountRepository.existsById(sourceAccountId)) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Source account not found"
        );
    }

    if (!accountRepository.existsById(targetAccountId)) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Target account not found"
        );
    }

    int debited = accountRepository.debitIfSufficient(
        sourceAccountId,
        amount.amount()
    );

    if (debited == 0) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Insufficient funds"
        );
    }

    int credited = accountRepository.credit(
        targetAccountId,
        amount.amount()
    );

    if (credited == 0) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Target account not found"
        );
    }
}


    @Override
    @Transactional(readOnly = true)
    public List<AccountSummary> findByOwner(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
            .map(this::toSummary)
            .toList();
    }

    private AccountSummary toSummary(Account a) {
        return new AccountSummary(a.getId(), a.getAccountNumber(), a.getBalance());
    }

    private String generateAccountNumber() {
        return "ACC" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
