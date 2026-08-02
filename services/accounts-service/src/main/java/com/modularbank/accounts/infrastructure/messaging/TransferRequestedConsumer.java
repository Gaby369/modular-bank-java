package com.modularbank.accounts.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modularbank.accounts.infrastructure.messaging.events.TransferRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_QUEUE;

@Component
@RequiredArgsConstructor
public class TransferRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final TransferRequestedProcessor processor;
    private final TransferFailureRecorder failureRecorder;

    @RabbitListener(queues = REQUESTED_QUEUE)
    public void consume(Message message) {

        TransferRequestedEvent event =
            deserialize(message);

        try {
            processor.process(event);

        } catch (ResponseStatusException exception) {
            failureRecorder.recordFailure(
                event,
                businessErrorMessage(exception)
            );
        }
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

        return exception.getStatusCode().toString();
    }
}
