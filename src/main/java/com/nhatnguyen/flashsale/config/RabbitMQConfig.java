package com.nhatnguyen.flashsale.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${flashsale.rabbitmq.exchange:flashsale.order.exchange}")
    private String orderExchangeName;

    @Value("${flashsale.rabbitmq.queue:flashsale.order.create.queue}")
    private String orderQueueName;

    @Value("${flashsale.rabbitmq.routing-key:flashsale.order.create}")
    private String orderRoutingKey;

    @Value("${flashsale.rabbitmq.dlx-exchange:flashsale.order.dlx.exchange}")
    private String dlxExchangeName;

    @Value("${flashsale.rabbitmq.dlq-queue:flashsale.order.dlq.queue}")
    private String dlqQueueName;

    @Value("${flashsale.rabbitmq.dlq-routing-key:flashsale.order.dlq}")
    private String dlqRoutingKey;

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(orderExchangeName, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(dlxExchangeName, true, false);
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(orderQueueName)
                .withArgument("x-dead-letter-exchange", dlxExchangeName)
                .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
                .build();
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(dlqQueueName).build();
    }

    @Bean
    public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue).to(orderExchange).with(orderRoutingKey);
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with(dlqRoutingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        return template;
    }
}
