package com.modularbank.accounts.infrastructure.outbox;

import com.modularbank.accounts.shared.observability.TraceContextStore;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.EXCHANGE;
import static com.modularbank.accounts.infrastructure.outbox.OutboxStatus.PENDING;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int MAXIMUM_ATTEMPTS = 5;

    private static final String CORRELATION_HEADER =
        "X-Correlation-Id";

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TraceContextStore traceContextStore;

    @Scheduled(
        fixedDelayString =
            "${outbox.publisher.fixed-delay-ms:1000}",
        initialDelayString =
            "${outbox.publisher.initial-delay-ms:2000}"
    )
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events =
            outboxEventRepository
                .findTop50ByStatusOrderByCreatedAtAsc(PENDING);

        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            try (
                Scope ignored = traceContextStore.restore(
                    event.getTraceparent(),
                    event.getTracestate()
                )
            ) {
                rabbitTemplate.send(
                    EXCHANGE,
                    event.getRoutingKey(),
                    createMessage(event)
                );
            }

            event.markPublished();
            outboxEventRepository.save(event);

        } catch (RuntimeException exception) {
            event.registerFailure(
                errorMessage(exception),
                MAXIMUM_ATTEMPTS
            );

            outboxEventRepository.save(event);
        }
    }

    private Message createMessage(OutboxEvent event) {
        MessageProperties properties =
            new MessageProperties();

        properties.setMessageId(
            event.getId().toString()
        );

        properties.setType(
            event.getEventType()
        );

        properties.setContentType(
            MessageProperties.CONTENT_TYPE_JSON
        );

        properties.setContentEncoding(
            StandardCharsets.UTF_8.name()
        );

        properties.setDeliveryMode(
            MessageDeliveryMode.PERSISTENT
        );

        properties.setHeader(
            "eventId",
            event.getId().toString()
        );

        properties.setHeader(
            "aggregateId",
            event.getAggregateId().toString()
        );

        properties.setHeader(
            "eventType",
            event.getEventType()
        );

        if (
            event.getCorrelationId() != null
                && !event.getCorrelationId().isBlank()
        ) {
            properties.setHeader(
                CORRELATION_HEADER,
                event.getCorrelationId()
            );
        }

        return new Message(
            event.getPayload()
                .getBytes(StandardCharsets.UTF_8),
            properties
        );
    }

    private String errorMessage(
        RuntimeException exception
    ) {
        if (exception.getMessage() != null) {
            return exception.getMessage();
        }

        return exception
            .getClass()
            .getSimpleName();
    }
}
