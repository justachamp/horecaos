package uz.horecaos.platform.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * ADR 0006's missing retry loop.
 *
 * <p>A {@code RETRY_PENDING} row used to wait on a Kafka redelivery, which only
 * arrives while the consumer stays assigned to the partition whose offset it
 * withheld. After a rebalance, a restart, or a deploy the row waited on nothing
 * at all — and a row parked behind an earlier sibling, whose offset is
 * deliberately acknowledged, waited on nothing from the moment it was parked.
 *
 * <p>Every clock here is the test's own fixed clock. Time is advanced by
 * building a worker at a later instant rather than by sleeping, so the
 * assertions are about the retry schedule rather than about how busy the machine
 * running them happens to be.
 */
class InboxRetryWorkerTests {

    private static final String CONSUMER = "retry-consumer";
    private static final String TOPIC = "tenancy.events";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e01");
    private static final UUID AGGREGATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e02");
    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private RecordingHandler handler;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();
        handler = new RecordingHandler(jdbc);
    }

    @Test
    void aRetryPendingItemIsDrivenWithoutAKafkaRedelivery() {
        handler.failNext();
        UUID eventId = UUID.randomUUID();
        assertThat(offer(NOW, eventId, "first", 0)).isEqualTo(InboxResult.RETRY_SCHEDULED);
        assertThat(status(eventId)).isEqualTo("RETRY_PENDING");

        assertThat(workerAt(NOW.plusSeconds(60)).redriveOnce()).isEqualTo(1);

        assertThat(status(eventId))
                .as("nothing else was ever going to drive this row once the partition moved on")
                .isEqualTo("PROCESSED");
        assertThat(sideEffectCount()).isEqualTo(1);
    }

    @Test
    void nothingIsDrivenBeforeItsRetryTimeArrives() {
        handler.failNext();
        offer(NOW, UUID.randomUUID(), "first", 0);

        assertThat(workerAt(NOW).redriveOnce())
                .as("the backoff exists to be waited out, not to be looked at")
                .isZero();
    }

    @Test
    void aParkedItemRunsOnlyOnceTheEarlierOneIsSettled() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        assertThat(offer(NOW, earlier, "first", 0, "TenantCreated", 99, "2026-08-25T09:00:00Z"))
                .isEqualTo(InboxResult.UNSUPPORTED);
        assertThat(offer(NOW, later, "second", 1, "TenantCreated", 1, "2026-08-25T09:30:00Z"))
                .isEqualTo(InboxResult.BLOCKED_BY_EARLIER);

        workerAt(NOW.plusSeconds(60)).redriveOnce();
        assertThat(status(later))
                .as("the blocker is still a dead letter, so the parked item must stay parked")
                .isEqualTo("RETRY_PENDING");
        assertThat(sideEffectCount()).isZero();

        resolve(earlier);
        workerAt(NOW.plusSeconds(120)).redriveOnce();

        assertThat(status(later)).isEqualTo("PROCESSED");
        assertThat(sideEffectCount()).isEqualTo(1);
    }

    @Test
    void anItemAbandonedByADeadWorkerIsRecoveredOnceItsLeaseExpires() {
        UUID eventId = UUID.randomUUID();
        handler.failNext();
        offer(NOW, eventId, "first", 0);
        abandonMidFlight(eventId, NOW);

        assertThat(workerAt(NOW.plusSeconds(60)).redriveOnce())
                .as("the lease still has four minutes to run, and taking the item now would "
                        + "run a second handler alongside a worker that may still be alive")
                .isZero();

        assertThat(workerAt(NOW.plusSeconds(600)).redriveOnce()).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PROCESSED");
    }

    private InboxResult offer(Instant at, UUID eventId, String value, long offset) {
        return offer(at, eventId, value, offset, "TenantCreated", 1, "2026-08-25T09:00:00Z");
    }

    private InboxResult offer(
            Instant at, UUID eventId, String value, long offset, String eventType, int version, String occurredAt) {
        return executorAt(at)
                .execute(
                        CONSUMER,
                        AGGREGATE.toString(),
                        body(eventId, value, eventType, version, occurredAt),
                        Map.of(),
                        TOPIC,
                        0,
                        offset);
    }

    private InboxExecutor executorAt(Instant at) {
        Clock clock = Clock.fixed(at, ZoneOffset.UTC);
        return new InboxExecutor(
                new JdbcInboxStore(jdbc, clock),
                new InboxHandlerRegistry(List.of(handler)),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SimpleMeterRegistry(),
                10);
    }

    private InboxRetryWorker workerAt(Instant at) {
        Clock clock = Clock.fixed(at, ZoneOffset.UTC);
        return new InboxRetryWorker(
                new JdbcInboxStore(jdbc, clock), executorAt(at), new InboxHandlerRegistry(List.of(handler)), 20);
    }

    /** What the ADR 0006 resolve endpoint leaves behind, without its authorization. */
    private void resolve(UUID eventId) {
        jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'RESOLVED', dead_lettered_at = NULL, resolved_at = :now,
                       resolved_by = 'operator', resolution_reason = 'handled out of band'
                 WHERE event_id = :id
                """)
                .param("id", eventId)
                .param("now", NOW.plusSeconds(90).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A worker that took the item and then died still holding the lease. */
    private void abandonMidFlight(UUID eventId, Instant startedAt) {
        jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'PROCESSING', processing_token = :token,
                       processing_started_at = :startedAt
                 WHERE event_id = :id
                """)
                .param("id", eventId)
                .param("token", UUID.randomUUID())
                .param("startedAt", startedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String body(UUID eventId, String value, String eventType, int version, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"tenantId":"%s",
                 "aggregateType":"Tenant","aggregateId":"%s","correlationId":"correlation-1",
                 "causationId":null,"occurredAt":"%s",
                 "payload":{"value":"%s"}}""".formatted(eventId, eventType, version, TENANT, AGGREGATE, occurredAt, value);
    }

    private String status(UUID eventId) {
        return jdbc.sql("SELECT status FROM integration.inbox_messages WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
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
                VALUES (:id, 'tenant-inbox-retry', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
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
        private volatile boolean failNext;

        private RecordingHandler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        void failNext() {
            failNext = true;
        }

        @Override
        public String consumerName() {
            return CONSUMER;
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
                    .param("consumer", CONSUMER)
                    .param("id", event.eventId())
                    .param("value", String.valueOf(event.payload().get("value")))
                    .update();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated transient handler failure");
            }
        }
    }
}
