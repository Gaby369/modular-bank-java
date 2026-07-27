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

import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.COMPLETED_ROUTING_KEY;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.EXCHANGE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_QUEUE;
import static com.modularbank.transfers.infrastructure.messaging.TransferMessagingConstants.FAILED_ROUTING_KEY;

@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public DirectExchange transfersExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue transferCompletedQueue() {
        return QueueBuilder
                .durable(COMPLETED_QUEUE)
                .build();
    }

    @Bean
    public Queue transferFailedQueue() {
        return QueueBuilder
                .durable(FAILED_QUEUE)
                .build();
    }

    @Bean
    public Binding transferCompletedBinding(
            @Qualifier("transferCompletedQueue") Queue queue,
            DirectExchange transfersExchange) {

        return BindingBuilder
                .bind(queue)
                .to(transfersExchange)
                .with(COMPLETED_ROUTING_KEY);
    }

    @Bean
    public Binding transferFailedBinding(
            @Qualifier("transferFailedQueue") Queue queue,
            DirectExchange transfersExchange) {

        return BindingBuilder
                .bind(queue)
                .to(transfersExchange)
                .with(FAILED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public ApplicationRunner declareRabbitTopology(
            RabbitAdmin rabbitAdmin,
            DirectExchange transfersExchange,
            @Qualifier("transferCompletedQueue") Queue completedQueue,
            @Qualifier("transferFailedQueue") Queue failedQueue,
            @Qualifier("transferCompletedBinding") Binding completedBinding,
            @Qualifier("transferFailedBinding") Binding failedBinding) {

        return args -> {
            rabbitAdmin.declareExchange(transfersExchange);

            rabbitAdmin.declareQueue(completedQueue);
            rabbitAdmin.declareQueue(failedQueue);

            rabbitAdmin.declareBinding(completedBinding);
            rabbitAdmin.declareBinding(failedBinding);
        };
    }
}
