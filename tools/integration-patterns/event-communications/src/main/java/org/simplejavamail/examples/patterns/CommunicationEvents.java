package org.simplejavamail.examples.patterns;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/** Passes a job ID through Kafka or RabbitMQ, then lets MailWork load and guard the actual email submission. */
public final class CommunicationEvents {
    private CommunicationEvents() { }

    @Configuration
    @ConditionalOnProperty(name = "events.engine", havingValue = "kafka", matchIfMissing = true)
    public static class KafkaEvents {
        private final MailWork work;
        public KafkaEvents(MailWork work) {
            this.work = work;
        }

        @Bean
        NewTopic mailTopic() {
            return new NewTopic("communications", 1, (short) 1);
        }

        @Bean
        PatternPorts.WorkTrigger kafkaTrigger(KafkaTemplate<String, String> events) {
            return id -> events.send("communications", id, id).join();
        }

        @KafkaListener(topics = "communications", groupId = "mail-workers")
        public void consume(String id) {
            // Returning lets the framework acknowledge this delivery. It says nothing about final mailbox delivery.
            // If the broker redelivers, MailWork's SQL guard prevents a second SMTP invocation.
            work.deliver(id);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "events.engine", havingValue = "rabbitmq")
    public static class RabbitEvents {
        private final MailWork work;
        public RabbitEvents(MailWork work) {
            this.work = work;
        }

        @Bean
        Queue mailQueue() {
            return new Queue("communications", true);
        }

        @Bean
        PatternPorts.WorkTrigger rabbitTrigger(RabbitTemplate events) {
            return id -> events.convertAndSend("communications", id);
        }

        @RabbitListener(queues = "communications")
        public void consume(String id) {
            // The same durable guard applies regardless of which broker transported the application job ID.
            work.deliver(id);
        }
    }
}
