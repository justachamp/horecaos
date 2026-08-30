package uz.horecaos.platform.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

class KafkaOutboxPublisherTests {

    private static KafkaContainer kafkaContainer;
    private static String bootstrapServers;

    @BeforeAll
    static void startKafka() {
        String externalBootstrapServers = System.getenv("HORECAOS_TEST_KAFKA_BOOTSTRAP_SERVERS");
        if (externalBootstrapServers != null && !externalBootstrapServers.isBlank()) {
            bootstrapServers = externalBootstrapServers;
            return;
        }

        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker or HORECAOS_TEST_KAFKA_BOOTSTRAP_SERVERS is required for Kafka integration tests");
        kafkaContainer = new KafkaContainer("apache/kafka:4.3.1");
        kafkaContainer.start();
        bootstrapServers = kafkaContainer.getBootstrapServers();
    }

    @AfterAll
    static void stopKafka() {
        if (kafkaContainer != null) {
            kafkaContainer.stop();
        }
    }

    @Test
    void publishesTheVersionedEnvelopePartitionKeyAndIdempotencyHeaders() throws Exception {
        String topic = "tenancy.events.test." + UUID.randomUUID();
        createTopic(topic);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ProducerConfig.ACKS_CONFIG,
                        "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                        true),
                new StringSerializer(),
                new StringSerializer());
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        try {
            JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
            KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(template, objectMapper, Duration.ofSeconds(10));
            UUID eventId = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120401");
            UUID tenantId = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120402");
            publisher.publish(new ClaimedOutboxEvent(
                    eventId,
                    "TenantCreated",
                    1,
                    tenantId,
                    "Tenant",
                    tenantId,
                    topic,
                    tenantId.toString(),
                    "request-42",
                    null,
                    Instant.parse("2026-08-19T01:00:00Z"),
                    "{\"tenantId\":\"" + tenantId + "\",\"slug\":\"tenant-a\"}",
                    "{\"traceId\":\"trace-42\"}",
                    1,
                    UUID.randomUUID()));

            var record = consumeOne(topic);
            assertThat(record.key()).isEqualTo(tenantId.toString());
            assertThat(record.value())
                    .contains("\"eventId\":\"" + eventId + "\"")
                    .contains("\"eventType\":\"TenantCreated\"")
                    .contains("\"tenantId\":\"" + tenantId + "\"")
                    .contains("\"payload\":{")
                    .contains("\"traceId\":\"trace-42\"");
            assertThat(new String(
                            record.headers().lastHeader("horecaos-event-id").value(), StandardCharsets.UTF_8))
                    .isEqualTo(eventId.toString());
            assertThat(new String(
                            record.headers()
                                    .lastHeader("horecaos-correlation-id")
                                    .value(),
                            StandardCharsets.UTF_8))
                    .isEqualTo("request-42");
        } finally {
            template.destroy();
            producerFactory.destroy();
            deleteTopic(topic);
        }
    }

    private static void createTopic(String topic) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
    }

    private static void deleteTopic(String topic) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.deleteTopics(List.of(topic)).all().get();
        }
    }

    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> consumeOne(String topic) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "horecaos-outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new AssertionError("Kafka did not return the outbox event before the test deadline");
        }
    }
}
