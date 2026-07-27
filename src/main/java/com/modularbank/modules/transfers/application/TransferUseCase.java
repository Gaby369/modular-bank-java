package com.modularbank.modules.transfers.application;

import com.modularbank.modules.accounts.application.AccountsService;
import com.modularbank.modules.audit.application.AuditService;
import com.modularbank.modules.notifications.application.NotificationsService;
import com.modularbank.modules.notifications.domain.NotificationType;
import com.modularbank.modules.transfers.application.dto.TransferRequest;
import com.modularbank.modules.transfers.domain.Transfer;
import com.modularbank.modules.transfers.infrastructure.TransferRepository;
import com.modularbank.shared.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferUseCase {

    private final TransferRepository transferRepository;
    private final AccountsService accountsService;
    private final NotificationsService notificationsService;
    private final AuditService auditService;

    @Transactional
    public Transfer execute(UUID userId, TransferRequest request) {
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Source and target accounts must be different");
        }

        boolean owns = accountsService.findByOwner(userId).stream()
            .anyMatch(a -> a.id().equals(request.sourceAccountId()));
        if (!owns) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source account does not belong to the authenticated user");
        }

        Money amount = Money.of(request.amount());

        accountsService.transfer(
            request.sourceAccountId(),
            request.targetAccountId(),
            amount,
            request.reference()
        );

        Transfer transfer = Transfer.builder()
            .sourceAccountId(request.sourceAccountId())
            .targetAccountId(request.targetAccountId())
            .amount(request.amount())
            .reference(request.reference())
            .build();
        transfer = transferRepository.save(transfer);

        notificationsService.send(userId, NotificationType.TRANSFER_SENT, Map.of(
            "amount", request.amount().toPlainString(),
            "targetAccountId", request.targetAccountId().toString()
        ));

        auditService.record(userId, "TRANSFER_EXECUTED", Map.of(
            "transferId", transfer.getId().toString(),
            "amount", request.amount().toPlainString()
        ));

        return transfer;
    }

    @Transactional(readOnly = true)
    public List<Transfer> getHistory(UUID accountId) {
        return transferRepository
            .findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(accountId, accountId);
    }
}
