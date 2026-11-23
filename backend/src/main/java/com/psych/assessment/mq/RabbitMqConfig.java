package com.psych.assessment.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String REPORT_EXCHANGE = "psych.report.exchange";
    public static final String REPORT_QUEUE = "psych.report.generate.queue";
    public static final String REPORT_ROUTING_KEY = "psych.report.generate";

    @Bean
    public DirectExchange reportExchange() {
        return new DirectExchange(REPORT_EXCHANGE, true, false);
    }

    @Bean
    public Queue reportQueue() {
        return QueueBuilder.durable(REPORT_QUEUE).build();
    }

    @Bean
    public Binding reportBinding(Queue reportQueue, DirectExchange reportExchange) {
        return BindingBuilder.bind(reportQueue).to(reportExchange).with(REPORT_ROUTING_KEY);
    }
}
