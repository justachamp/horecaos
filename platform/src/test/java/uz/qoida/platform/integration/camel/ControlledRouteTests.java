package uz.qoida.platform.integration.camel;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.integration.api.ExternalEventEnvelope;
import uz.qoida.platform.integration.api.InboxHandler;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.integration.camel.ControlledCommandRoute.ControlledCommand;
import uz.qoida.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.qoida.platform.integration.inbox.EnvelopeValidator;
import uz.qoida.platform.integration.inbox.InboxExecutor;
import uz.qoida.platform.integration.inbox.InboxHandlerRegistry;
import uz.qoida.platform.integration.inbox.InboxResult;
import uz.qoida.platform.integration.inbox.InboxRetryWorker;
import uz.qoida.platform.integration.inbox.JdbcInboxStore;
import uz.qoida.platform.integration.outbox.ControlledResultOutbox;
import uz.qoida.platform.integration.outbox.JdbcOutboxStore;
import uz.qoida.platform.support.TestDatabase;

/**
 * ADR 0007's exit criterion, end to end: a versioned command arrives on a Kafka
 * record, passes the ADR 0005 inbox, reaches a provider through a Camel route,
 * and its canonical result is written to the ADR 0004 outbox in the same
 * transaction as the inbox transition.
 *
 * <p>The four things asserted are the four the ADR names, and each is a bug that
 * has cost somebody money somewhere: a duplicated command must invoke the
 * provider once, a transient failure must retry under the <em>same</em>
 * idempotency key, a permanent rejection must never be retried, and an uncertain
 * outcome must be reconciled by a query rather than repeated.
 *
 * <p>The Kafka broker itself is not started. The record is offered to
 * {@link InboxExecutor#execute}, which is the exact method the listener calls
 * with a consumer record's fields; starting a broker would test Spring Kafka's
 * deserialisation and nothing about this path.
 */
class ControlledRouteTests {

    private static final String CONSUMER = "controlled-consumer";
    private static final String TOPIC = "integration.controlled.commands";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f01");
    private static final Instant NOW = Instant.parse("2026-08-25T11:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private ControlledFakeProvider provider;
    private CamelContext camel;
    private ProducerTemplate producer;
    private CommandHandler handler;

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
    void setUp() throws Exception {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();

        provider = ControlledFakeProvider.start();
        camel = new DefaultCamelContext();
        camel.addRoutes(new ControlledCommandRoute(provider.baseUrl(), new ProviderExceptionClassifier()));
        camel.start();
        producer = camel.createProducerTemplate();
        handler = new CommandHandler(producer, new ControlledResultOutbox(new JdbcOutboxStore(jdbc)));
    }

    @AfterEach
    void tearDown() {
        camel.stop();
        provider.close();
    }

    @Test
    void aDuplicatedCommandInvokesTheProviderOnceAndEmitsOneResult() {
        UUID commandId = UUID.randomUUID();

        assertThat(offer(NOW, commandId, "SUCCESS", 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(NOW, commandId, "SUCCESS", 1)).isEqualTo(InboxResult.DUPLICATE_IGNORED);
        assertThat(offer(NOW, commandId, "SUCCESS", 2)).isEqualTo(InboxResult.DUPLICATE_IGNORED);

        assertThat(provider.sideEffectCount())
                .as("at-least-once delivery must not become at-least-once provider side effects")
                .isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(1);
        assertThat(outboxStatus(commandId)).isEqualTo("SUCCESS");
    }

    @Test
    void aTransientFailureRetriesUnderTheSameIdempotencyKey() {
        UUID commandId = UUID.randomUUID();

        assertThat(offer(NOW, commandId, "SERVER_ERROR", 0))
                .as("a 500 is the provider's fault, not the command's")
                .isEqualTo(InboxResult.RETRY_SCHEDULED);
        assertThat(outboxRows()).isZero();

        provider.forceScenario(ControlledFakeProvider.Scenario.SUCCESS);
        assertThat(workerAt(NOW.plusSeconds(60)).redriveOnce()).isEqualTo(1);

        assertThat(provider.requests())
                .extracting(ControlledFakeProvider.RecordedRequest::idempotencyKey)
                .as("a retry that mints a new key defeats the provider deduplication it depends on")
                .containsExactly(commandId.toString(), commandId.toString());
        assertThat(provider.sideEffectCount()).isEqualTo(1);
        assertThat(outboxStatus(commandId)).isEqualTo("SUCCESS");
    }

    @Test
    void aPermanentRejectionIsRecordedAndNeverRetried() {
        UUID commandId = UUID.randomUUID();

        assertThat(offer(NOW, commandId, "PERMANENT_REJECTION", 0))
                .as("a 400 is a settled answer; the command was handled, and the answer was no")
                .isEqualTo(InboxResult.PROCESSED);

        assertThat(workerAt(NOW.plusSeconds(600)).redriveOnce())
                .as("retrying a 400 produces the same 400 forever while looking like an outage")
                .isZero();
        assertThat(provider.requests()).hasSize(1);
        assertThat(outboxStatus(commandId)).isEqualTo("REJECTED");
    }

    @Test
    void anUncertainOutcomeIsReconciledByQueryRatherThanRepeated() {
        UUID commandId = UUID.randomUUID();

        assertThat(offer(NOW, commandId, "ACCEPTED_THEN_TIMEOUT", 0)).isEqualTo(InboxResult.PROCESSED);

        assertThat(provider.requests())
                .as("the provider accepted the command and lost the reply; sending it again "
                        + "would book a second one, which is why UNCERTAIN is not RETRYABLE")
                .hasSize(1);
        assertThat(provider.sideEffectCount()).isEqualTo(1);
        assertThat(outboxStatus(commandId))
                .as("the status query found the accepted command, so the result is settled")
                .isEqualTo("SUCCESS");
    }

    private InboxResult offer(Instant at, UUID commandId, String scenario, long offset) {
        return executorAt(at).execute(
                CONSUMER, commandId.toString(), body(commandId, scenario),
                Map.of(), TOPIC, 0, offset);
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
        return new InboxRetryWorker(
                new JdbcInboxStore(jdbc, Clock.fixed(at, ZoneOffset.UTC)),
                executorAt(at),
                new InboxHandlerRegistry(List.of(handler)),
                20);
    }

    private static String body(UUID commandId, String scenario) {
        return """
                {"eventId":"%s","eventType":"ControlledCommandIssued","eventVersion":1,"tenantId":"%s",
                 "aggregateType":"ControlledCommand","aggregateId":"%s","correlationId":"correlation-1",
                 "causationId":null,"occurredAt":"2026-08-25T10:00:00Z",
                 "payload":{"scenario":"%s"}}"""
                .formatted(commandId, TENANT, commandId, scenario);
    }

    private long outboxRows() {
        return jdbc.sql("SELECT count(*) FROM integration.outbox_events").query(Long.class).single();
    }

    private String outboxStatus(UUID commandId) {
        return jdbc.sql("""
                SELECT payload ->> 'status' FROM integration.outbox_events
                 WHERE aggregate_id = :id
                """).param("id", commandId).query(String.class).single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-controlled', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    /**
     * The domain side of the route: it sends a provider-neutral command and
     * decides what the outcome means. The route never makes that decision, which
     * is ADR 0007's boundary.
     */
    private static final class CommandHandler implements InboxHandler<Map<String, Object>> {

        private final ProducerTemplate producer;
        private final ControlledResultOutbox results;

        private CommandHandler(ProducerTemplate producer, ControlledResultOutbox results) {
            this.producer = producer;
            this.results = results;
        }

        @Override
        public String consumerName() {
            return CONSUMER;
        }

        @Override
        public String eventType() {
            return "ControlledCommandIssued";
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
            ControlledCommand command = new ControlledCommand(
                    event.eventId(), event.tenantId(), String.valueOf(event.payload().get("scenario")));

            ProviderOutcome outcome = producer.requestBody(
                    ControlledCommandRoute.COMMAND_ENDPOINT, command, ProviderOutcome.class);

            if (outcome.mayRetryDirectly()) {
                // Thrown rather than recorded, so the inbox schedules the retry
                // under its own backoff. Recording a retryable outcome as a
                // result would settle a command that is not settled.
                throw new IllegalStateException("Provider call is retryable: " + outcome.errorCode());
            }
            results.appendResult(command.commandId(), command.tenantId(), outcome, event.occurredAt());
        }
    }
}
