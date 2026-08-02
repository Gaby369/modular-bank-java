package com.modularbank.modules.accounts.infrastructure;

import com.modularbank.modules.accounts.application.AccountsService;
import com.modularbank.modules.accounts.application.dto.AccountSummary;
import com.modularbank.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(
    name = "accounts.client.mode",
    havingValue = "remote"
)
public class RemoteAccountsService implements AccountsService {

    private final RestClient restClient;

    public RemoteAccountsService(
        RestClient.Builder builder,
        @Value("${accounts.client.base-url}") String baseUrl,
        @Value("${accounts.client.internal-api-key}") String apiKey
    ) {
        this.restClient = builder
            .baseUrl(baseUrl)
            .defaultHeader(
                "X-Internal-Api-Key",
                apiKey
            )
            .build();
    }

    @Override
    public AccountSummary createAccount(UUID userId) {
        AccountSummary account = execute(() ->
            restClient.post()
                .uri("/internal/accounts")
                .body(new CreateAccountRequest(userId))
                .retrieve()
                .body(AccountSummary.class)
        );

        if (account == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Accounts Service returned an empty response"
            );
        }

        return account;
    }

    @Override
    public Money getBalance(UUID accountId) {
        BalanceResponse response = execute(() ->
            restClient.get()
                .uri(
                    "/internal/accounts/{accountId}/balance",
                    accountId
                )
                .retrieve()
                .body(BalanceResponse.class)
        );

        if (response == null || response.amount() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Accounts Service returned an empty balance"
            );
        }

        return Money.of(response.amount());
    }

    @Override
    public void debit(
        UUID accountId,
        Money amount,
        String reference
    ) {
        execute(() ->
            restClient.post()
                .uri(
                    "/internal/accounts/{accountId}/debit",
                    accountId
                )
                .body(
                    new AccountOperationRequest(
                        amount.amount(),
                        reference
                    )
                )
                .retrieve()
                .toBodilessEntity()
        );
    }

    @Override
    public void credit(
        UUID accountId,
        Money amount,
        String reference
    ) {
        execute(() ->
            restClient.post()
                .uri(
                    "/internal/accounts/{accountId}/credit",
                    accountId
                )
                .body(
                    new AccountOperationRequest(
                        amount.amount(),
                        reference
                    )
                )
                .retrieve()
                .toBodilessEntity()
        );
    }

    @Override
    public void transfer(
        UUID sourceAccountId,
        UUID targetAccountId,
        Money amount,
        String reference
    ) {
        execute(() ->
            restClient.post()
                .uri("/internal/accounts/transfer")
                .body(
                    new InternalTransferRequest(
                        sourceAccountId,
                        targetAccountId,
                        amount.amount(),
                        reference
                    )
                )
                .retrieve()
                .toBodilessEntity()
        );
    }

    @Override
    public List<AccountSummary> findByOwner(UUID userId) {
        List<AccountSummary> accounts = execute(() ->
            restClient.get()
                .uri(
                    "/internal/accounts/owner/{userId}",
                    userId
                )
                .retrieve()
                .body(
                    new ParameterizedTypeReference<
                        List<AccountSummary>
                    >() {
                    }
                )
        );

        return accounts == null ? List.of() : accounts;
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();

        } catch (RestClientResponseException exception) {
            throw new ResponseStatusException(
                exception.getStatusCode(),
                exception.getResponseBodyAsString(),
                exception
            );

        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Accounts Service is unavailable",
                exception
            );
        }
    }

    private record CreateAccountRequest(
        UUID userId
    ) {
    }

    private record AccountOperationRequest(
        BigDecimal amount,
        String reference
    ) {
    }

    private record InternalTransferRequest(
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String reference
    ) {
    }

    private record BalanceResponse(
        BigDecimal amount
    ) {
    }
}