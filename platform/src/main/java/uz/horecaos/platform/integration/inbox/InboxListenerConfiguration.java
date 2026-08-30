package uz.horecaos.platform.integration.inbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

/**
 * Container configuration for ADR 0005 consumption.
 *
 * <p>Manual acknowledgement and disabled auto-commit are the two settings that
 * make the inbox meaningful. With auto-commit, an offset could advance past a
 * record whose effect never committed, and the event would be lost rather than
 * retried.
 */
@Configuration(proxyBeanMethods = false)
public class InboxListenerConfiguration {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> inboxListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${horecaos.messaging.inbox.concurrency:1}") int concurrency,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        // Spring's own property, honoured rather than ignored. A hand-rolled
        // factory does not consult it unless it is passed on, and this one did
        // not: every listener started regardless, including in the nine tests
        // that deliberately point `bootstrap-servers` at a dead port because
        // they are about the HTTP layer. Those consumers then blocked
        // KafkaConsumer.close() for its thirty-second default at every context
        // teardown — a hundred and seventy-nine seconds of a thirteen-minute
        // build, and two thousand eight hundred connection warnings that made
        // the log unreadable while they did it.
        factory.setAutoStartup(autoStartup);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);

        // Ordering per aggregate depends on partition assignment, so the
        // container must never reorder within a partition.
        factory.getContainerProperties().setPollTimeout(3_000);
        return factory;
    }
}
