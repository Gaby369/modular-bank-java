package com.modularbank.transfers.infrastructure.client;

import com.modularbank.transfers.application.ports.AccountsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class HttpAccountsClient implements AccountsClient {

    private final RestClient restClient;

    public HttpAccountsClient(
        RestClient.Builder builder,
        @Value("${accounts.service.base-url}") String baseUrl,
        @Value("${accounts.service.internal-api-key}") String apiKey
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
    public boolean ownsAccount(
        UUID userId,
        UUID accountId
    ) {
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

        if (accounts == null) {
            return false;
        }

        return accounts.stream()
            .anyMatch(account ->
                accountId.equals(account.id())
            );
    }

    @Override
    public void transfer(
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String reference
    ) {
        execute(() ->
            restClient.post()
                .uri("/internal/accounts/transfer")
                .body(
                    new TransferCommand(
                        sourceAccountId,
                        targetAccountId,
                        amount,
                        reference
                    )
                )
                .retrieve()
                .toBodilessEntity()
        );
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

    private record AccountSummary(
        UUID id,
        String accountNumber,
        BigDecimal balance
    ) {
    }

    private record TransferCommand(
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String reference
    ) {
    }
}