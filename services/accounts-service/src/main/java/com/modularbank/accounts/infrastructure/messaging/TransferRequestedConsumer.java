package com.modularbank.accounts.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.accounts.infrastructure.messaging.events.TransferRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_QUEUE;

@Component
@RequiredArgsConstructor
public class TransferRequestedConsumer {

    private static final String CORRELATION_HEADER =
        "X-Correlation-Id";

    private static final String CORRELATION_MDC_KEY =
        "correlationId";

    private static final Pattern SAFE_CORRELATION_ID =
        Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final ObjectMapper objectMapper;
    private final TransferRequestedProcessor processor;
    private final TransferFailureRecorder failureRecorder;

    @RabbitListener(queues = REQUESTED_QUEUE)
    public void consume(Message message) {
        String correlationId =
            resolveCorrelationId(message);

        MDC.put(
            CORRELATION_MDC_KEY,
            correlationId
        );

        try {
            TransferRequestedEvent event =
                deserialize(message);

            try {
                processor.process(
                    event,
                    correlationId
                );

            } catch (ResponseStatusException exception) {
                failureRecorder.recordFailure(
                    event,
                    businessErrorMessage(exception),
                    correlationId
                );
            }

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

    private TransferRequestedEvent deserialize(
        Message message
    ) {
        String payload = new String(
            message.getBody(),
            StandardCharsets.UTF_8
        );

        try {
            return objectMapper.readValue(
                payload,
                TransferRequestedEvent.class
            );

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not deserialize TransferRequestedEvent",
                exception
            );
        }
    }

    private String businessErrorMessage(
        ResponseStatusException exception
    ) {
        if (exception.getReason() != null) {
            return exception.getReason();
        }

        return exception
            .getStatusCode()
            .toString();
    }
}
