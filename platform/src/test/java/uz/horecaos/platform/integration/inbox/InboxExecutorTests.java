package uz.horecaos.platform.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.InboxHandler;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0005 exit criterion: a duplicate Kafka record cannot duplicate a durable
 * business effect, and the offset is acknowledged only after the inbox and
 * business transaction commit.
 */
class InboxExecutorTests {

    private static final String CONSUMER = "test-consumer";
    private static final String TOPIC = "tenancy.events";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d01");
    private static final UUID AGGREGATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d02");
    private static final UUID OTHER_AGGREGATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d03");
    private static final Instant TEST_NOW = Instant.parse("2026-08-20T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InboxExecutor executor;
    private RecordingHandler handler;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();

        Clock clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC);
        handler = new RecordingHandler(jdbc);
        executor = new InboxExecutor(
                new JdbcInboxStore(jdbc, clock),
                new InboxHandlerRegistry(List.of(handler)),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SimpleMeterRegistry(),
                10);
    }

    @Test
    void processesAnEventOnce() {
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0)).isEqualTo(InboxResult.PROCESSED);

        assertThat(handler.handled()).hasSize(1);
        assertThat(status(eventId)).isEqualTo("PROCESSED");
    }

    @Test
    void aRedeliveredRecordRunsTheHandlerExactlyOnce() {
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(eventId, "first", 1)).isEqualTo(InboxResult.DUPLICATE_IGNORED);
        assertThat(offer(eventId, "first", 2)).isEqualTo(InboxResult.DUPLICATE_IGNORED);

        assertThat(handler.handled())
                .as("at-least-once delivery must not become at-least-once side effects")
                .hasSize(1);
        assertThat(sideEffectCount()).isEqualTo(1);
    }

    @Test
    void everyOutcomeExceptRetryAllowsTheOffsetToBeAcknowledged() {
        assertThat(InboxResult.PROCESSED.mayAcknowledgeOffset()).isTrue();
        assertThat(InboxResult.DUPLICATE_IGNORED.mayAcknowledgeOffset()).isTrue();
        assertThat(InboxResult.UNSUPPORTED.mayAcknowledgeOffset()).isTrue();
        assertThat(InboxResult.CONTRACT_COLLISION.mayAcknowledgeOffset()).isTrue();
        assertThat(InboxResult.BLOCKED_BY_EARLIER.mayAcknowledgeOffset())
                .as("a partition carries many aggregates; holding the offset would stall all of them")
                .isTrue();
        assertThat(InboxResult.RETRY_SCHEDULED.mayAcknowledgeOffset())
                .as("an unacknowledged offset is how a transient failure gets another attempt")
                .isFalse();
    }

    @Test
    void aHandlerFailureLeavesNeitherTheEffectNorTheProcessedState() {
        handler.failNext();
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0)).isEqualTo(InboxResult.RETRY_SCHEDULED);

        assertThat(sideEffectCount())
                .as("the business effect and the PROCESSED transition roll back together")
                .isZero();
        assertThat(status(eventId)).isEqualTo("RETRY_PENDING");
    }

    @Test
    void aRetriedItemProcessesOnceWhenTheHandlerRecovers() {
        handler.failNext();
        UUID eventId = UUID.randomUUID();
        offer(eventId, "first", 0);

        assertThat(offer(eventId, "first", 1)).isEqualTo(InboxResult.PROCESSED);
        assertThat(sideEffectCount()).isEqualTo(1);
    }

    @Test
    void theSameEventIdWithADifferentPayloadIsQuarantined() {
        UUID eventId = UUID.randomUUID();
        offer(eventId, "original", 0);

        assertThat(offer(eventId, "altered", 1))
                .as("a producer reusing an event id for new content has violated immutability")
                .isEqualTo(InboxResult.CONTRACT_COLLISION);
        assertThat(status(eventId))
                .as("a processed row is true evidence and must not be rewritten to describe another record")
                .isEqualTo("PROCESSED");
        assertThat(sideEffectCount()).isEqualTo(1);
    }

    @Test
    void aCollisionOnAnUnprocessedItemQuarantinesIt() {
        handler.failNext();
        UUID eventId = UUID.randomUUID();
        offer(eventId, "original", 0);
        assertThat(status(eventId)).isEqualTo("RETRY_PENDING");

        assertThat(offer(eventId, "altered", 1)).isEqualTo(InboxResult.CONTRACT_COLLISION);
        assertThat(status(eventId)).isEqualTo("DEAD_LETTER");
    }

    @Test
    void anUnsupportedVersionIsDeadLetteredRatherThanRetriedForever() {
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0, "TenantCreated", 99)).isEqualTo(InboxResult.UNSUPPORTED);
        assertThat(status(eventId)).isEqualTo("DEAD_LETTER");
        assertThat(handler.handled()).isEmpty();
    }

    @Test
    void anUnknownEventTypeIsDeadLettered() {
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0, "SomethingElseHappened", 1)).isEqualTo(InboxResult.UNSUPPORTED);
    }

    @Test
    void aMalformedEnvelopeIsRejectedWithoutAnInboxRow() {
        InboxResult result = executor.execute(CONSUMER, AGGREGATE.toString(), "{not json", Map.of(), TOPIC, 0, 0);

        assertThat(result).isEqualTo(InboxResult.INVALID_ENVELOPE);
        assertThat(jdbc.sql("SELECT count(*) FROM integration.inbox_messages")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aRecordKeyThatDisagreesWithTheAggregateIsRejected() {
        InboxResult result = executor.execute(
                CONSUMER,
                UUID.randomUUID().toString(),
                body(UUID.randomUUID(), "first", "TenantCreated", 1),
                Map.of(),
                TOPIC,
                0,
                0);

        assertThat(result)
                .as("the key decides partitioning, so a mismatch means ordering guarantees do not hold")
                .isEqualTo(InboxResult.INVALID_ENVELOPE);
    }

    @Test
    void aHeaderThatDisagreesWithTheBodyIsRejected() {
        UUID eventId = UUID.randomUUID();

        InboxResult result = executor.execute(
                CONSUMER,
                AGGREGATE.toString(),
                body(eventId, "first", "TenantCreated", 1),
                Map.of("horecaos-tenant-id", UUID.randomUUID().toString()),
                TOPIC,
                0,
                0);

        assertThat(result)
                .as("a header claiming a different tenant is what a cross-tenant attempt looks like")
                .isEqualTo(InboxResult.INVALID_ENVELOPE);
    }

    @Test
    void twoConsumersProcessTheSameEventIndependently() {
        RecordingHandler second = new RecordingHandler(jdbc, "other-consumer");
        InboxExecutor other = new InboxExecutor(
                new JdbcInboxStore(jdbc, Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC)),
                new InboxHandlerRegistry(List.of(second)),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(db.dataSource())),
                new SimpleMeterRegistry(),
                10);

        UUID eventId = UUID.randomUUID();
        offer(eventId, "first", 0);
        InboxResult otherResult = other.execute(
                "other-consumer",
                AGGREGATE.toString(),
                body(eventId, "first", "TenantCreated", 1),
                Map.of(),
                TOPIC,
                0,
                0);

        assertThat(otherResult)
                .as("deduplication is per consumer; a second consumer must still see the event")
                .isEqualTo(InboxResult.PROCESSED);
        assertThat(handler.handled()).hasSize(1);
        assertThat(second.handled()).hasSize(1);
    }

    @Test
    void concurrentRedeliveriesProduceExactlyOneEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        int attempts = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<InboxResult>> calls =
                    java.util.Collections.nCopies(attempts, () -> offer(eventId, "first", 0));
            List<Future<InboxResult>> results = pool.invokeAll(calls);
            for (Future<InboxResult> result : results) {
                result.get();
            }
        }

        assertThat(sideEffectCount())
                .as("%d concurrent redeliveries must produce one effect", attempts)
                .isEqualTo(1);
    }

    @Test
    void aLaterEventIsParkedBehindAnEarlierDeadLetterForTheSameAggregate() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        assertThat(offer(earlier, "first", 0, "TenantCreated", 99)).isEqualTo(InboxResult.UNSUPPORTED);

        InboxResult result = executor.execute(
                CONSUMER,
                AGGREGATE.toString(),
                body(later, "second", "TenantCreated", 1, AGGREGATE, "2026-08-20T09:30:00Z"),
                Map.of(),
                TOPIC,
                0,
                1);

        assertThat(result)
                .as("the earlier event's offset was acknowledged when it dead-lettered, so nothing "
                        + "else stops the next one applying on top of a transition that never happened")
                .isEqualTo(InboxResult.BLOCKED_BY_EARLIER);
        assertThat(status(later)).isEqualTo("RETRY_PENDING");
        assertThat(errorCode(later)).isEqualTo("BLOCKED_BY_EARLIER_EVENT");
        assertThat(sideEffectCount()).isZero();
    }

    @Test
    void aParkedEventDoesNotSpendItsRetryBudget() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        offer(earlier, "first", 0, "TenantCreated", 99);

        executor.execute(
                CONSUMER,
                AGGREGATE.toString(),
                body(later, "second", "TenantCreated", 1, AGGREGATE, "2026-08-20T09:30:00Z"),
                Map.of(),
                TOPIC,
                0,
                1);

        assertThat(attemptCount(later))
                .as("arriving second is not a failed attempt, and charging it retry budget would "
                        + "eventually dead-letter a perfectly good event")
                .isZero();
    }

    @Test
    void anotherAggregateIsNotHeldUpByADeadLetter() {
        offer(UUID.randomUUID(), "first", 0, "TenantCreated", 99);

        UUID other = UUID.randomUUID();
        InboxResult result = executor.execute(
                CONSUMER,
                OTHER_AGGREGATE.toString(),
                body(other, "elsewhere", "TenantCreated", 1, OTHER_AGGREGATE, "2026-08-20T09:30:00Z"),
                Map.of(),
                TOPIC,
                0,
                1);

        assertThat(result)
                .as("blocking is per aggregate; one stuck tenant must not stop every other tenant")
                .isEqualTo(InboxResult.PROCESSED);
    }

    @Test
    void aScheduledRetryCarriesJitterRatherThanTheBareExponential() {
        handler.failNext();
        UUID eventId = UUID.randomUUID();

        assertThat(offer(eventId, "first", 0)).isEqualTo(InboxResult.RETRY_SCHEDULED);

        // One failed attempt against a two-second initial delay: undithered,
        // every replica would come back at exactly the same instant.
        assertThat(availableAt(eventId)).isBefore(TEST_NOW.plusSeconds(2)).isAfterOrEqualTo(TEST_NOW.plusSeconds(1));
    }

    private InboxResult offer(UUID eventId, String value, long offset) {
        return offer(eventId, value, offset, "TenantCreated", 1);
    }

    private InboxResult offer(UUID eventId, String value, long offset, String eventType, int version) {
        return executor.execute(
                CONSUMER, AGGREGATE.toString(), body(eventId, value, eventType, version), Map.of(), TOPIC, 0, offset);
    }

    private static String body(UUID eventId, String value, String eventType, int version) {
        return body(eventId, value, eventType, version, AGGREGATE, "2026-08-20T09:00:00Z");
    }

    private static String body(
            UUID eventId, String value, String eventType, int version, UUID aggregateId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"tenantId":"%s",
                 "aggregateType":"Tenant","aggregateId":"%s","correlationId":"correlation-1",
                 "causationId":null,"occurredAt":"%s",
                 "payload":{"value":"%s"}}""".formatted(eventId, eventType, version, TENANT, aggregateId, occurredAt, value);
    }

    private String status(UUID eventId) {
        return jdbc.sql("SELECT status FROM integration.inbox_messages WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private String errorCode(UUID eventId) {
        return jdbc.sql("SELECT last_error_code FROM integration.inbox_messages WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private int attemptCount(UUID eventId) {
        return jdbc.sql("SELECT attempt_count FROM integration.inbox_messages WHERE event_id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }

    private Instant availableAt(UUID eventId) {
        return jdbc.sql("SELECT available_at FROM integration.inbox_messages WHERE event_id = :id")
                .param("id", eventId)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private long sideEffectCount() {
        return jdbc.sql("SELECT count(*) FROM integration.inbox_test_effects WHERE consumer_name = :consumer")
                .param("consumer", CONSUMER)
                .query(Long.class)
                .single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-inbox', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        // Dropped rather than truncated: an earlier run may have left a table
        // with a different shape, which would fail every handler insert and
        // present as an unrelated executor bug.
        jdbc.sql("DROP TABLE IF EXISTS integration.inbox_test_effects").update();
        jdbc.sql("""
                CREATE TABLE integration.inbox_test_effects (
                    consumer_name text NOT NULL, id uuid NOT NULL, value text NOT NULL,
                    PRIMARY KEY (consumer_name, id))
                """).update();
    }

    /** A handler whose effect is a real row, so atomicity can be observed. */
    private static final class RecordingHandler implements InboxHandler<Map<String, Object>> {

        private final JdbcClient jdbc;
        private final String consumerName;
        private final List<UUID> handled = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean failNext;

        private RecordingHandler(JdbcClient jdbc) {
            this(jdbc, CONSUMER);
        }

        private RecordingHandler(JdbcClient jdbc, String consumerName) {
            this.jdbc = jdbc;
            this.consumerName = consumerName;
        }

        void failNext() {
            failNext = true;
        }

        List<UUID> handled() {
            return List.copyOf(handled);
        }

        @Override
        public String consumerName() {
            return consumerName;
        }

        @Override
        public String eventType() {
            return "TenantCreated";
        }

        @Override
        public int eventVersion() {
            return 1;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<Map<String, Object>> payloadType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }

        @Override
        public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
            jdbc.sql("""
                    INSERT INTO integration.inbox_test_effects (consumer_name, id, value)
                    VALUES (:consumer, :id, :value)
                    """)
                    .param("consumer", consumerName)
                    .param("id", event.eventId())
                    .param("value", String.valueOf(event.payload().get("value")))
                    .update();
            handled.add(event.eventId());
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated transient handler failure");
            }
        }
    }
}
