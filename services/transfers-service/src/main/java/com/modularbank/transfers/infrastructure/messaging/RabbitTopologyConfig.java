package com.modularbank.transfers.infrastructure.messaging;

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

import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_DLQ;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_DLQ_ROUTING_KEY;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_ROUTING_KEY;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.DEAD_LETTER_EXCHANGE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.EXCHANGE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_DLQ;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_DLQ_ROUTING_KEY;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_ROUTING_KEY;

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
    public Queue transferCompletedQueue() {
        return QueueBuilder
            .durable(COMPLETED_QUEUE)
            .withArgument(
                "x-dead-letter-exchange",
                DEAD_LETTER_EXCHANGE
            )
            .withArgument(
                "x-dead-letter-routing-key",
                COMPLETED_DLQ_ROUTING_KEY
            )
            .build();
    }

    @Bean
    public Queue transferFailedQueue() {
        return QueueBuilder
            .durable(FAILED_QUEUE)
            .withArgument(
                "x-dead-letter-exchange",
                DEAD_LETTER_EXCHANGE
            )
            .withArgument(
                "x-dead-letter-routing-key",
                FAILED_DLQ_ROUTING_KEY
            )
            .build();
    }

    @Bean
    public Queue transferCompletedDeadLetterQueue() {
        return QueueBuilder
            .durable(COMPLETED_DLQ)
            .build();
    }

    @Bean
    public Queue transferFailedDeadLetterQueue() {
        return QueueBuilder
            .durable(FAILED_DLQ)
            .build();
    }

    @Bean
    public Binding transferCompletedBinding(
        @Qualifier("transferCompletedQueue")
        Queue queue,
        @Qualifier("transfersExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(COMPLETED_ROUTING_KEY);
    }

    @Bean
    public Binding transferFailedBinding(
        @Qualifier("transferFailedQueue")
        Queue queue,
        @Qualifier("transfersExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(FAILED_ROUTING_KEY);
    }

    @Bean
    public Binding transferCompletedDeadLetterBinding(
        @Qualifier("transferCompletedDeadLetterQueue")
        Queue queue,
        @Qualifier("transferDeadLetterExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(COMPLETED_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding transferFailedDeadLetterBinding(
        @Qualifier("transferFailedDeadLetterQueue")
        Queue queue,
        @Qualifier("transferDeadLetterExchange")
        DirectExchange exchange
    ) {
        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(FAILED_DLQ_ROUTING_KEY);
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
        @Qualifier("transferCompletedQueue")
        Queue completedQueue,
        @Qualifier("transferFailedQueue")
        Queue failedQueue,
        @Qualifier("transferCompletedDeadLetterQueue")
        Queue completedDeadLetterQueue,
        @Qualifier("transferFailedDeadLetterQueue")
        Queue failedDeadLetterQueue,
        @Qualifier("transferCompletedBinding")
        Binding completedBinding,
        @Qualifier("transferFailedBinding")
        Binding failedBinding,
        @Qualifier("transferCompletedDeadLetterBinding")
        Binding completedDeadLetterBinding,
        @Qualifier("transferFailedDeadLetterBinding")
        Binding failedDeadLetterBinding
    ) {
        return args -> {
            rabbitAdmin.declareExchange(transfersExchange);
            rabbitAdmin.declareExchange(deadLetterExchange);

            rabbitAdmin.declareQueue(completedQueue);
            rabbitAdmin.declareQueue(failedQueue);
            rabbitAdmin.declareQueue(
                completedDeadLetterQueue
            );
            rabbitAdmin.declareQueue(
                failedDeadLetterQueue
            );

            rabbitAdmin.declareBinding(completedBinding);
            rabbitAdmin.declareBinding(failedBinding);
            rabbitAdmin.declareBinding(
                completedDeadLetterBinding
            );
            rabbitAdmin.declareBinding(
                failedDeadLetterBinding
            );
        };
    }
}
