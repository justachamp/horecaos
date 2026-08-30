package uz.horecaos.platform.integration.events;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Provisions the complete ADR 0032 topic catalogue through {@code KafkaAdmin}. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfiguration {

    /**
     * Spring Kafka creates missing topics from this bean before producers use
     * them. Existing topics are never silently altered here: changing a
     * partition count or retention is an explicit operational migration, not an
     * application-start side effect.
     */
    @Bean
    org.springframework.kafka.core.KafkaAdmin.NewTopics horecaosKafkaTopics() {
        NewTopic[] topics = KafkaTopicCatalog.all().stream()
                .map(topic -> TopicBuilder.name(topic.name())
                        .partitions(topic.partitions())
                        .replicas(topic.replicationFactor())
                        .configs(Map.of(
                                TopicConfig.CLEANUP_POLICY_CONFIG,
                                topic.cleanupPolicy(),
                                TopicConfig.RETENTION_MS_CONFIG,
                                Long.toString(topic.retention().toMillis())))
                        .build())
                .toArray(NewTopic[]::new);
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(topics);
    }
}
