package com.modularbank.transfers.application;

import com.modularbank.transfers.application.dto.TransferRequest;
import com.modularbank.transfers.application.ports.AccountsClient;
import com.modularbank.transfers.domain.Transfer;
import com.modularbank.transfers.infrastructure.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferUseCase {

    private final TransferRepository transferRepository;
    private final AccountsClient accountsClient;

    public Transfer execute(
        UUID userId,
        TransferRequest request
    ) {
        if (request.sourceAccountId()
            .equals(request.targetAccountId())) {

            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Source and target accounts must be different"
            );
        }

        boolean ownsSourceAccount =
            accountsClient.ownsAccount(
                userId,
                request.sourceAccountId()
            );

        if (!ownsSourceAccount) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Source account does not belong to the authenticated user"
            );
        }

        Transfer transfer = Transfer.builder()
            .sourceAccountId(request.sourceAccountId())
            .targetAccountId(request.targetAccountId())
            .amount(request.amount())
            .reference(request.reference())
            .status("PENDING")
            .build();

        transfer = transferRepository.save(transfer);

        try {
            accountsClient.transfer(
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amount(),
                request.reference()
            );

            transfer.setStatus("COMPLETED");
            return transferRepository.save(transfer);

        } catch (RuntimeException exception) {
            transfer.setStatus("FAILED");
            transferRepository.save(transfer);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<Transfer> getHistory(
        UUID userId,
        UUID accountId
    ) {
        boolean ownsAccount =
            accountsClient.ownsAccount(
                userId,
                accountId
            );

        if (!ownsAccount) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Account does not belong to the authenticated user"
            );
        }

        return transferRepository
            .findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(
                accountId,
                accountId
            );
    }
}