package com.modularbank.accounts.api.internal.dto;

import java.util.UUID;

public record InternalCreateAccountRequest(
    UUID userId
) {
}