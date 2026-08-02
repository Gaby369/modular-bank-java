package com.modularbank.modules.notifications.infrastructure.messaging;

public final class NotificationsMessagingConstants {

    public static final String EXCHANGE =
        "bank.transfers.exchange";

    public static final String REQUESTED_ROUTING_KEY =
        "transfer.requested.v1";

    public static final String REQUESTED_QUEUE =
        "notifications.transfer.requested.queue";

    private NotificationsMessagingConstants() {
    }
}
