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
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.EXCHANGE;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_QUEUE;
import static com.modularbank.accounts.infrastructure.messaging.TransferMessagingConstants.REQUESTED_ROUTING_KEY;

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
    public Queue transferRequestedQueue() {
        return QueueBuilder
                .durable(REQUESTED_QUEUE)
                .build();
    }

    @Bean
    public Binding transferRequestedBinding(
            Queue transferRequestedQueue,
            DirectExchange transfersExchange) {

        return BindingBuilder
                .bind(transferRequestedQueue)
                .to(transfersExchange)
                .with(REQUESTED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public ApplicationRunner declareRabbitTopology(
            RabbitAdmin rabbitAdmin,
            DirectExchange transfersExchange,
            Queue transferRequestedQueue,
            Binding transferRequestedBinding) {

        return args -> {
            rabbitAdmin.declareExchange(transfersExchange);
            rabbitAdmin.declareQueue(transferRequestedQueue);
            rabbitAdmin.declareBinding(transferRequestedBinding);
        };
    }
}
