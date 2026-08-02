package com.modularbank.transfers.infrastructure.client;

import com.modularbank.transfers.application.ports.AccountsClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    private static final Logger LOGGER =
        LoggerFactory.getLogger(HttpAccountsClient.class);

    private static final String CIRCUIT_BREAKER_NAME =
        "accountsService";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpAccountsClient(
        RestClient.Builder builder,
        CircuitBreakerRegistry circuitBreakerRegistry,
        @Value("${accounts.service.base-url}")
        String baseUrl,
        @Value("${accounts.service.internal-api-key}")
        String apiKey,
        @Value("${accounts.service.connect-timeout-ms:2000}")
        int connectTimeoutMs,
        @Value("${accounts.service.read-timeout-ms:3000}")
        int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        this.restClient = builder
            .requestFactory(requestFactory)
            .baseUrl(baseUrl)
            .defaultHeader(
                "X-Internal-Api-Key",
                apiKey
            )
            .build();

        this.circuitBreaker =
            circuitBreakerRegistry.circuitBreaker(
                CIRCUIT_BREAKER_NAME
            );

        this.circuitBreaker
            .getEventPublisher()
            .onStateTransition(event ->
                LOGGER.warn(
                    "Accounts Service circuit breaker transition={}",
                    event.getStateTransition()
                )
            );
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
        Supplier<T> protectedOperation =
            CircuitBreaker.decorateSupplier(
                circuitBreaker,
                () -> invoke(operation)
            );

        try {
            return protectedOperation.get();

        } catch (CallNotPermittedException exception) {
            throw unavailable(
                "Accounts Service circuit breaker is OPEN",
                exception
            );

        } catch (
            AccountsServiceUnavailableException exception
        ) {
            throw unavailable(
                "Accounts Service is unavailable",
                exception
            );
        }
    }

    private <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();

        } catch (
            RestClientResponseException exception
        ) {
            throw new ResponseStatusException(
                exception.getStatusCode(),
                exception.getResponseBodyAsString(),
                exception
            );

        } catch (ResourceAccessException exception) {
            throw new AccountsServiceUnavailableException(
                "Could not connect to Accounts Service",
                exception
            );
        }
    }

    private ResponseStatusException unavailable(
        String message,
        RuntimeException cause
    ) {
        return new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            message,
            cause
        );
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
