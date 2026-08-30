package uz.qoida.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;

/** ADR 0032: the broker catalogue, event catalogue, and operator docs agree. */
class KafkaTopicCatalogTests {

    @Test
    void everyPublishedEventTopicIsDeliberatelyProvisioned() {
        Set<String> provisioned = KafkaTopicCatalog.all().stream()
                .map(KafkaTopicCatalog.TopicSpecification::name)
                .collect(Collectors.toSet());

        assertThat(EventCatalog.all())
                .extracting(EventContract::topic)
                .containsOnlyElementsOf(provisioned);
    }

    @Test
    void everyProvisionedTopicHasAtLeastOnePublishedContract() {
        Set<String> published = EventCatalog.all().stream()
                .map(EventContract::topic)
                .collect(Collectors.toSet());

        assertThat(KafkaTopicCatalog.all())
                .extracting(KafkaTopicCatalog.TopicSpecification::name)
                .containsOnlyElementsOf(published);
    }

    @Test
    void everyTopicIsExplicitlyPartitionedReplicatedAndDeletedOnItsOwnRetention() {
        assertThat(KafkaTopicCatalog.all()).allSatisfy(topic -> {
            var newTopic = TopicBuilder.name(topic.name())
                    .partitions(topic.partitions())
                    .replicas(topic.replicationFactor())
                    .config("cleanup.policy", topic.cleanupPolicy())
                    .config("retention.ms", Long.toString(topic.retention().toMillis()))
                    .build();

            assertThat(newTopic.numPartitions()).isEqualTo(topic.partitions());
            assertThat(newTopic.replicationFactor()).isEqualTo(topic.replicationFactor());
            assertThat(newTopic.configs()).containsEntry("cleanup.policy", "delete")
                    .containsEntry("retention.ms", Long.toString(topic.retention().toMillis()));
        });
    }

    @Test
    void topicProvisioningTableDocumentsEveryCodeOwnedPolicy() throws Exception {
        String catalogue = Files.readString(Path.of("docs/domains/events.md"), StandardCharsets.UTF_8);

        KafkaTopicCatalog.all().forEach(topic -> assertThat(catalogue)
                .contains("`%s` | %d | %d | `%s` | `%s`".formatted(
                        topic.name(), topic.partitions(), topic.replicationFactor(),
                        topic.retention(), topic.cleanupPolicy())));
    }
}
