package com.modularbank.modules.notifications.infrastructure.messaging;

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

import static com.modularbank.modules.notifications.infrastructure.messaging.NotificationsMessagingConstants.EXCHANGE;
import static com.modularbank.modules.notifications.infrastructure.messaging.NotificationsMessagingConstants.REQUESTED_QUEUE;
import static com.modularbank.modules.notifications.infrastructure.messaging.NotificationsMessagingConstants.REQUESTED_ROUTING_KEY;

@Configuration(proxyBeanMethods = false)
public class NotificationsRabbitTopologyConfig {

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
    public Queue notificationsTransferRequestedQueue() {
        return QueueBuilder
            .durable(REQUESTED_QUEUE)
            .build();
    }

    @Bean
    public Binding notificationsTransferRequestedBinding(
        @Qualifier("notificationsTransferRequestedQueue")
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
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public ApplicationRunner declareNotificationsRabbitTopology(
        RabbitAdmin rabbitAdmin,
        @Qualifier("transfersExchange")
        DirectExchange transfersExchange,
        @Qualifier("notificationsTransferRequestedQueue")
        Queue requestedQueue,
        @Qualifier("notificationsTransferRequestedBinding")
        Binding requestedBinding
    ) {
        return args -> {
            rabbitAdmin.declareExchange(transfersExchange);
            rabbitAdmin.declareQueue(requestedQueue);
            rabbitAdmin.declareBinding(requestedBinding);
        };
    }
}
