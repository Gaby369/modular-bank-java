package com.modularbank.transfers.infrastructure.messaging;

public final class TransferMessagingConstants {

    public static final String EXCHANGE =
        "bank.transfers.exchange";

    public static final String DEAD_LETTER_EXCHANGE =
        "bank.transfers.dlx";

    public static final String REQUESTED_QUEUE =
        "transfer.requested.queue";

    public static final String COMPLETED_QUEUE =
        "transfer.completed.queue";

    public static final String FAILED_QUEUE =
        "transfer.failed.queue";

    public static final String REQUESTED_DLQ =
        "transfer.requested.dlq";

    public static final String COMPLETED_DLQ =
        "transfer.completed.dlq";

    public static final String FAILED_DLQ =
        "transfer.failed.dlq";

    public static final String REQUESTED_ROUTING_KEY =
        "transfer.requested.v1";

    public static final String COMPLETED_ROUTING_KEY =
        "transfer.completed.v1";

    public static final String FAILED_ROUTING_KEY =
        "transfer.failed.v1";

    public static final String REQUESTED_DLQ_ROUTING_KEY =
        "transfer.requested.dlq";

    public static final String COMPLETED_DLQ_ROUTING_KEY =
        "transfer.completed.dlq";

    public static final String FAILED_DLQ_ROUTING_KEY =
        "transfer.failed.dlq";

    private TransferMessagingConstants() {
    }
}
