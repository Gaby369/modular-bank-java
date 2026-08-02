package com.modularbank.modules.notifications.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.modules.notifications.application.NotificationsService;
import com.modularbank.modules.notifications.domain.NotificationType;
import com.modularbank.modules.notifications.infrastructure.messaging.events.TransferRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.modularbank.modules.notifications.infrastructure.messaging.NotificationsMessagingConstants.REQUESTED_QUEUE;

@Component
@RequiredArgsConstructor
public class TransferRequestedNotificationListener {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(TransferRequestedNotificationListener.class);

    private static final String CORRELATION_HEADER =
        "X-Correlation-Id";

    private static final String CORRELATION_MDC_KEY =
        "correlationId";

    private static final Pattern SAFE_CORRELATION_ID =
        Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final ObjectMapper objectMapper;
    private final NotificationsService notificationsService;

    @RabbitListener(queues = REQUESTED_QUEUE)
    public void consume(Message message) {
        String correlationId = resolveCorrelationId(message);
        MDC.put(CORRELATION_MDC_KEY, correlationId);

        try {
            TransferRequestedEvent event = deserialize(message);

            LOGGER.info(
                "Received TransferRequested.v1 transferId={}",
                event.transferId()
            );

            notificationsService.send(
                event.userId(),
                NotificationType.TRANSFER_SENT,
                Map.of(
                    "transferId", event.transferId().toString(),
                    "sourceAccountId", event.sourceAccountId().toString(),
                    "targetAccountId", event.targetAccountId().toString(),
                    "amount", event.amount().toPlainString(),
                    "reference", event.reference()
                )
            );

        } catch (Exception exception) {
            // Notifications are a best-effort side effect: a failure here
            // must not hold up or dead-letter the funds-moving flow, so it
            // is logged and swallowed rather than rethrown.
            LOGGER.error(
                "Failed to create notification for TransferRequested.v1",
                exception
            );

        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    private String resolveCorrelationId(Message message) {
        Object headerValue =
            message.getMessageProperties()
                .getHeaders()
                .get(CORRELATION_HEADER);

        if (headerValue != null) {
            String candidate = headerValue.toString();

            if (SAFE_CORRELATION_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }

        return UUID.randomUUID().toString();
    }

    private TransferRequestedEvent deserialize(Message message)
        throws Exception {

        String payload = new String(
            message.getBody(),
            StandardCharsets.UTF_8
        );

        return objectMapper.readValue(
            payload,
            TransferRequestedEvent.class
        );
    }
}
