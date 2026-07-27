package com.modularbank.transfers.api;

import com.modularbank.transfers.application.TransferUseCase;
import com.modularbank.transfers.application.dto.TransferRequest;
import com.modularbank.transfers.domain.Transfer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransfersController {

    private final TransferUseCase transferUseCase;

    @PostMapping
    public ResponseEntity<?> executeTransfer(
        @RequestBody @Valid TransferRequest request,
        Authentication authentication
    ) {
        UUID userId =
            (UUID) authentication.getPrincipal();

        try {
            Transfer transfer =
                transferUseCase.execute(
                    userId,
                    request
                );

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(transfer);

        } catch (ResponseStatusException exception) {
            String message =
                exception.getReason() != null
                    ? exception.getReason()
                    : "Transfer error";

            return ResponseEntity
                .status(exception.getStatusCode())
                .body(Map.of("message", message));

        } catch (Exception exception) {
            String message =
                exception.getMessage() != null
                    ? exception.getMessage()
                    : exception.getClass().getSimpleName();

            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", message));
        }
    }

    @GetMapping
    public ResponseEntity<?> getHistory(
        @RequestParam UUID accountId,
        Authentication authentication
    ) {
        UUID userId =
            (UUID) authentication.getPrincipal();

        try {
            List<Transfer> transfers =
                transferUseCase.getHistory(
                    userId,
                    accountId
                );

            return ResponseEntity.ok(transfers);

        } catch (ResponseStatusException exception) {
            String message =
                exception.getReason() != null
                    ? exception.getReason()
                    : "History error";

            return ResponseEntity
                .status(exception.getStatusCode())
                .body(Map.of("message", message));
        }
    }
}