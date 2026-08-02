package com.modularbank.accounts.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.DEAD_LETTER_EXCHANGE;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.EXCHANGE;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_DLQ;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_DLQ_ROUTING_KEY;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_QUEUE;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_ROUTING_KEY;

@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(
        ConnectionFactory connectionFactory
    ) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public DirectExchange transfersExchange() {
        return new DirectExchange(
            EXCHANGE,
            true,
            false
        );
    }

    @Bean
    public DirectExchange transferDeadLetterExchange() {
        return new DirectExchange(
            DEAD_LETTER_EXCHANGE,
            true,
            false
        );
    }

    @Bean
    public Queue transferRequestedQueue() {
        return QueueBuilder
            .durable(REQUESTED_QUEUE)
            .withArgument(
                "x-dead-letter-exchange",
                DEAD_LETTER_EXCHANGE
            )
            .withArgument(
                "x-dead-letter-routing-key",
                REQUESTED_DLQ_ROUTING_KEY
            )
            .build();
    }

    @Bean
    public Queue transferRequestedDeadLetterQueue() {
        return QueueBuilder
            .durable(REQUESTED_DLQ)
            .build();
    }

    @Bean
    public Binding transferRequestedBinding(
        @Qualifier("transferRequestedQueue")
        Queue queue,
        @Qualifier("transfersExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(REQUESTED_ROUTING_KEY);
    }

    @Bean
    public Binding transferRequestedDeadLetterBinding(
        @Qualifier("transferRequestedDeadLetterQueue")
        Queue queue,
        @Qualifier("transferDeadLetterExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(REQUESTED_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public ApplicationRunner declareRabbitTopology(
        RabbitAdmin rabbitAdmin,
        @Qualifier("transfersExchange")
        DirectExchange transfersExchange,
        @Qualifier("transferDeadLetterExchange")
        DirectExchange deadLetterExchange,
        @Qualifier("transferRequestedQueue")
        Queue requestedQueue,
        @Qualifier("transferRequestedDeadLetterQueue")
        Queue requestedDeadLetterQueue,
        @Qualifier("transferRequestedBinding")
        Binding requestedBinding,
        @Qualifier("transferRequestedDeadLetterBinding")
        Binding deadLetterBinding
    ) {
        return args -> {
            rabbitAdmin.declareExchange(transfersExchange);
            rabbitAdmin.declareExchange(deadLetterExchange);

            rabbitAdmin.declareQueue(requestedQueue);
            rabbitAdmin.declareQueue(
                requestedDeadLetterQueue
            );

            rabbitAdmin.declareBinding(requestedBinding);
            rabbitAdmin.declareBinding(
                deadLetterBinding
            );
        };
    }
}
