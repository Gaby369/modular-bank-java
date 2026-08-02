package com.modularbank.transfers.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.transfers.infrastructure.messaging.events.TransferCompletedEvent;
import com.modularbank.transfers.infrastructure.messaging.events.TransferFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_QUEUE;

@Component
@RequiredArgsConstructor
public class TransferResultConsumer {

    private final ObjectMapper objectMapper;
    private final TransferResultProcessor processor;

    @RabbitListener(queues = COMPLETED_QUEUE)
    public void consumeCompleted(Message message) {
        TransferCompletedEvent event =
            deserialize(
                message,
                TransferCompletedEvent.class
            );

        processor.markCompleted(event);
    }

    @RabbitListener(queues = FAILED_QUEUE)
    public void consumeFailed(Message message) {
        TransferFailedEvent event =
            deserialize(
                message,
                TransferFailedEvent.class
            );

        processor.markFailed(event);
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
