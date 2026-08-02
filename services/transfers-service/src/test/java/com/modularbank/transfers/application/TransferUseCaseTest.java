package com.modularbank.transfers.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modularbank.transfers.application.dto.TransferRequest;
import com.modularbank.transfers.application.ports.AccountsClient;
import com.modularbank.transfers.domain.Transfer;
import com.modularbank.transfers.infrastructure.TransferRepository;
import com.modularbank.transfers.infrastructure.outbox.OutboxEvent;
import com.modularbank.transfers.infrastructure.outbox.OutboxEventRepository;
import com.modularbank.transfers.infrastructure.outbox.OutboxStatus;
import com.modularbank.transfers.shared.observability.TraceContextStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferUseCaseTest {

    private final TransferRepository transferRepository =
        mock(TransferRepository.class);

    private final AccountsClient accountsClient =
        mock(AccountsClient.class);

    private final OutboxEventRepository outboxEventRepository =
        mock(OutboxEventRepository.class);

    private final ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final TraceContextStore traceContextStore =
        mock(TraceContextStore.class);

    private final TransferUseCase transferUseCase =
        new TransferUseCase(
            transferRepository,
            accountsClient,
            outboxEventRepository,
            objectMapper,
            traceContextStore
        );

    @BeforeEach
    void setUpTraceContext() {
        when(traceContextStore.capture()).thenReturn(
            new TraceContextStore.StoredTraceContext(
                null,
                null
            )
        );
    }

    @Test
    void shouldCreatePendingTransferAndPendingOutboxEvent() {
        UUID userId = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

        UUID sourceAccountId = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

        UUID targetAccountId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID transferId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        TransferRequest request =
            new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal("25.00"),
                "outbox-unit-test"
            );

        when(
            accountsClient.ownsAccount(
                userId,
                sourceAccountId
            )
        ).thenReturn(true);

        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> {
                Transfer transfer =
                    invocation.getArgument(0);

                transfer.setId(transferId);
                return transfer;
            });

        Transfer result =
            transferUseCase.execute(userId, request);

        assertEquals(transferId, result.getId());
        assertEquals("PENDING", result.getStatus());
        assertEquals(
            new BigDecimal("25.00"),
            result.getAmount()
        );

        ArgumentCaptor<Transfer> transferCaptor =
            ArgumentCaptor.forClass(Transfer.class);

        verify(transferRepository)
            .save(transferCaptor.capture());

        Transfer savedTransfer =
            transferCaptor.getValue();

        assertEquals(
            sourceAccountId,
            savedTransfer.getSourceAccountId()
        );

        assertEquals(
            targetAccountId,
            savedTransfer.getTargetAccountId()
        );

        assertEquals(
            "PENDING",
            savedTransfer.getStatus()
        );

        ArgumentCaptor<OutboxEvent> outboxCaptor =
            ArgumentCaptor.forClass(OutboxEvent.class);

        verify(outboxEventRepository)
            .save(outboxCaptor.capture());

        OutboxEvent savedOutbox =
            outboxCaptor.getValue();

        assertEquals(
            transferId,
            savedOutbox.getAggregateId()
        );

        assertEquals(
            "TransferRequested.v1",
            savedOutbox.getEventType()
        );

        assertEquals(
            "transfer.requested.v1",
            savedOutbox.getRoutingKey()
        );

        assertEquals(
            OutboxStatus.PENDING,
            savedOutbox.getStatus()
        );

        assertEquals(0, savedOutbox.getAttempts());

        assertTrue(
            savedOutbox
                .getPayload()
                .contains(transferId.toString())
        );

        assertTrue(
            savedOutbox
                .getPayload()
                .contains("outbox-unit-test")
        );
    }

    @Test
    void shouldRejectTransferWhenSourceAccountIsNotOwned() {
        UUID userId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        UUID sourceAccountId = UUID.fromString(
            "66666666-6666-6666-6666-666666666666"
        );

        UUID targetAccountId = UUID.fromString(
            "77777777-7777-7777-7777-777777777777"
        );

        TransferRequest request =
            new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal("25.00"),
                "unauthorized-test"
            );

        when(
            accountsClient.ownsAccount(
                userId,
                sourceAccountId
            )
        ).thenReturn(false);

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> transferUseCase.execute(
                    userId,
                    request
                )
            );

        assertEquals(
            403,
            exception
                .getStatusCode()
                .value()
        );

        verify(
            accountsClient
        ).ownsAccount(
            userId,
            sourceAccountId
        );

        verify(
            transferRepository,
            never()
        ).save(any(Transfer.class));

        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void shouldRejectTransferToSameAccount() {
        UUID userId = UUID.fromString(
            "88888888-8888-8888-8888-888888888888"
        );

        UUID accountId = UUID.fromString(
            "99999999-9999-9999-9999-999999999999"
        );

        TransferRequest request =
            new TransferRequest(
                accountId,
                accountId,
                new BigDecimal("25.00"),
                "same-account-test"
            );

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> transferUseCase.execute(
                    userId,
                    request
                )
            );

        assertEquals(
            422,
            exception
                .getStatusCode()
                .value()
        );

        verifyNoInteractions(
            accountsClient,
            transferRepository,
            outboxEventRepository
        );
    }
}
