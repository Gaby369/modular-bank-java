package com.modularbank.transfers.infrastructure.client;

public final class AccountsServiceUnavailableException
    extends RuntimeException {

    public AccountsServiceUnavailableException(
        String message,
        Throwable cause
    ) {
        super(message, cause);
    }
}
