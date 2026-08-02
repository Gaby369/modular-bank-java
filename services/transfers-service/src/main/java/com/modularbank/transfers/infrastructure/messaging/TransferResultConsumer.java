package com.modularbank.transfers.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.transfers.infrastructure.messaging.events.TransferCompletedEvent;
import com.modularbank.transfers.infrastructure.messaging.events.TransferFailedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_QUEUE;

@Component
@RequiredArgsConstructor
public class TransferResultConsumer {

    private static final String CORRELATION_HEADER =
        "X-Correlation-Id";

    private static final String CORRELATION_MDC_KEY =
        "correlationId";

    private static final Pattern SAFE_CORRELATION_ID =
        Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final ObjectMapper objectMapper;
    private final TransferResultProcessor processor;

    @RabbitListener(queues = COMPLETED_QUEUE)
    public void consumeCompleted(Message message) {
        String correlationId =
            resolveCorrelationId(message);

        MDC.put(
            CORRELATION_MDC_KEY,
            correlationId
        );

        try {
            TransferCompletedEvent event =
                deserialize(
                    message,
                    TransferCompletedEvent.class
                );

            processor.markCompleted(event);

        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    @RabbitListener(queues = FAILED_QUEUE)
    public void consumeFailed(Message message) {
        String correlationId =
            resolveCorrelationId(message);

        MDC.put(
            CORRELATION_MDC_KEY,
            correlationId
        );

        try {
            TransferFailedEvent event =
                deserialize(
                    message,
                    TransferFailedEvent.class
                );

            processor.markFailed(event);

        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    private String resolveCorrelationId(
        Message message
    ) {
        Object headerValue =
            message
                .getMessageProperties()
                .getHeaders()
                .get(CORRELATION_HEADER);

        if (headerValue != null) {
            String candidate =
                headerValue.toString();

            if (
                SAFE_CORRELATION_ID
                    .matcher(candidate)
                    .matches()
            ) {
                return candidate;
            }
        }

        return UUID.randomUUID().toString();
    }

    private <T> T deserialize(
        Message message,
        Class<T> eventClass
    ) {
        String payload = new String(
            message.getBody(),
            StandardCharsets.UTF_8
        );

        try {
            return objectMapper.readValue(
                payload,
                eventClass
            );

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not deserialize transfer result event",
                exception
            );
        }
    }
}
