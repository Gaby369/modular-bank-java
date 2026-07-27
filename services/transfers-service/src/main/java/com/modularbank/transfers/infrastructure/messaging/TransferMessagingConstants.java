package com.modularbank.transfers.infrastructure.messaging;

public final class TransferMessagingConstants {

    public static final String EXCHANGE =
            "bank.transfers.exchange";

    public static final String REQUESTED_QUEUE =
            "transfer.requested.queue";

    public static final String COMPLETED_QUEUE =
            "transfer.completed.queue";

    public static final String FAILED_QUEUE =
            "transfer.failed.queue";

    public static final String REQUESTED_ROUTING_KEY =
            "transfer.requested.v1";

    public static final String COMPLETED_ROUTING_KEY =
            "transfer.completed.v1";

    public static final String FAILED_ROUTING_KEY =
            "transfer.failed.v1";

    private TransferMessagingConstants() {
    }
}
