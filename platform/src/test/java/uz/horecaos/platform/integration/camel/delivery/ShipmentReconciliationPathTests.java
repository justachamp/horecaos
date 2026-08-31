package uz.horecaos.platform.integration.camel.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.inbox.EnvelopeValidator;
import uz.horecaos.platform.integration.inbox.InboxExecutor;
import uz.horecaos.platform.integration.inbox.InboxHandlerRegistry;
import uz.horecaos.platform.integration.inbox.InboxResult;
import uz.horecaos.platform.integration.inbox.InboxRetryWorker;
import uz.horecaos.platform.integration.inbox.JdbcInboxStore;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0007's production command path, end to end.
 *
 * <p>A versioned command arrives as a Kafka record, the ADR 0005 inbox
 * deduplicates it, the ADR 0007 delivery route calls a courier partner, and the
 * canonical result is written to the ADR 0004 outbox in the same transaction as
 * the inbox {@code PROCESSED} transition. Until this path existed the same
 * sequence was proved only by {@code ControlledCommandRoute} in test sources,
 * against a route that never ships.
 *
 * <p>The partner is scripted rather than mocked, for the reason
 * {@code DeliveryRouteTests} gives: the behaviour under test is what the route
 * and the handler do <em>next</em> after each of the four outcomes, and a mocked
 * gateway would test the mock. The Kafka broker is not started — the record is
 * offered to {@link InboxExecutor#execute}, the exact method the listener calls
 * with a consumer record's fields, and starting a broker would test Spring
 * Kafka's deserialisation and nothing about this path.
 */
class ShipmentReconciliationPathTests {

    private static final String TOPIC = "fulfillment.commands";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e02");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e03");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e04");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e05");
    private static final Instant NOW = Instant.parse("2026-08-25T11:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private CamelContext camel;
    private ScriptedPartner partner;
    private ShipmentReconciliationHandler handler;

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
    void setUp() throws Exception {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();

        partner = new ScriptedPartner();
        camel = new DefaultCamelContext();
        camel.addRoutes(new DeliveryRouteBuilder(processor()));
        camel.start();

        handler = new ShipmentReconciliationHandler(
                camel.createProducerTemplate(),
                lookup(),
                new ShipmentReconciliationOutbox(
                        new JdbcOutboxStore(jdbc), JsonMapper.builder().build(), fixedClock()),
                new SimpleMeterRegistry(),
                fixedClock());
    }

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("a command arrives, the route runs, and the result is an outbox row")
    void aCommandRunsTheRouteAndEmitsItsResult() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.success(Map.of("state", "CONFIRMED"), "ext-1"));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.PROCESSED);

        assertThat(partner.queries).isEqualTo(1);
        // The whole point of the record: the outcome left as a durable row for
        // the relay rather than as a return value to a caller who, by the time
        // this runs, no longer exists.
        assertThat(outboxRows()).isEqualTo(1);
        assertThat(resolution(operation)).isEqualTo("CONFIRMED");
        assertThat(outboxTopic(operation)).isEqualTo("fulfillment.events");
    }

    @Test
    @DisplayName("a duplicated command calls the partner once and emits one result")
    void aDuplicateProducesExactlyOneExternalEffect() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.success(Map.of("state", "CONFIRMED"), "ext-1"));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(operation, 1)).isEqualTo(InboxResult.DUPLICATE_IGNORED);
        assertThat(offer(operation, 2)).isEqualTo(InboxResult.DUPLICATE_IGNORED);

        assertThat(partner.queries)
                .as("at-least-once delivery must not become at-least-once provider calls")
                .isEqualTo(1);
        assertThat(outboxRows())
                .as("two results for one command would settle the same shipment twice, "
                        + "and a consumer cannot tell which one is current")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the partner holding the shipment settles it as CONFIRMED")
    void aSuccessfulQuerySettlesAsConfirmed() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.success(Map.of("state", "DELIVERING"), "ext-1"));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(resolution(operation)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("the partner having no such shipment settles it as ABSENT")
    void aRejectedQuerySettlesAsAbsent() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.rejected("NOT_FOUND", "no such claim"));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.PROCESSED);

        // The only answer that makes re-issuing the original booking safe, which
        // is why it is a distinct value from UNRESOLVED and not a synonym.
        assertThat(resolution(operation)).isEqualTo("ABSENT");
        assertThat(providerStatus(operation)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a retryable query is retried under the same key, not settled")
    void aRetryableQueryRetriesRatherThanSettling() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.retryable("PROVIDER_ERROR_503", "unavailable", null));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.RETRY_SCHEDULED);
        assertThat(outboxRows())
                .as("recording a retryable outcome as a result settles a shipment nobody has "
                        + "established anything about")
                .isZero();

        partner.onQuery(ProviderOutcome.success(Map.of("state", "CONFIRMED"), "ext-1"));
        assertThat(workerAt(NOW.plusSeconds(120)).redriveOnce()).isEqualTo(1);

        assertThat(partner.idempotencyKeys)
                .as("a retry that mints a new key defeats the provider deduplication it depends on")
                .containsExactly(operation.toString(), operation.toString());
        assertThat(resolution(operation)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an uncertain query that outlives its budget settles as UNRESOLVED")
    void anUnansweredQuerySettlesAsUnresolvedOnTheLastAttempt() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        // One attempt only, so this offer is also the last one. An unanswerable
        // case must become a fact somebody can act on rather than disappearing
        // into a dead letter nobody reads.
        assertThat(offer(operation, 0, 1)).isEqualTo(InboxResult.PROCESSED);

        assertThat(resolution(operation)).isEqualTo("UNRESOLVED");
        assertThat(providerStatus(operation)).isEqualTo("UNCERTAIN");
    }

    @Test
    @DisplayName("a command naming another tenant's binding never reaches the partner")
    void aCrossTenantCommandIsRefused() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.success(Map.of(), "ext-1"));

        // The binding is real and belongs to TENANT. The envelope claims
        // OTHER_TENANT, which is producer-controlled input and proves nothing.
        assertThat(offerFor(OTHER_TENANT, operation, 0, 10)).isEqualTo(InboxResult.PROCESSED);

        assertThat(partner.queries)
                .as("one tenant's command must not reach another tenant's partner account")
                .isZero();
        assertThat(outboxRows())
                .as("and no fact may be emitted about a shipment that is not this tenant's")
                .isZero();
    }

    @Test
    @DisplayName("a command naming a capability this platform does not have is refused")
    void aMalformedCommandNeverReachesThePartner() {
        UUID operation = UUID.randomUUID();
        partner.onQuery(ProviderOutcome.success(Map.of(), "ext-1"));

        assertThat(executorAt(NOW, 10)
                        .execute(
                                ShipmentReconciliationHandler.CONSUMER_NAME,
                                operation.toString(),
                                body(TENANT, operation).replace("CREATE_ON_DEMAND_SHIPMENT", "RM -RF"),
                                Map.of(),
                                TOPIC,
                                0,
                                0))
                .isEqualTo(InboxResult.PROCESSED);

        // A topic is not a trusted caller. The capability is echoed into the
        // settlement event, whose schema closes the field to an enum, so an
        // unchecked echo would let one topic decide what appears on another.
        assertThat(partner.queries).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("the provider call runs with no database transaction open")
    void theProviderCallDoesNotHoldAPooledConnection() {
        UUID operation = UUID.randomUUID();
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        partner.onQuery(ProviderOutcome.success(Map.of(), "ext-1"));
        partner.observe(() -> insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive()));

        assertThat(offer(operation, 0)).isEqualTo(InboxResult.PROCESSED);

        // The reason ExternalWorkInboxHandler exists. The pool is ten wide and
        // shared by every module; a courier that has stopped answering must cost
        // this handler its thread and nothing else.
        assertThat(insideTransaction).isFalse();
        assertThat(resolution(operation)).isEqualTo("CONFIRMED");
    }

    private InboxResult offer(UUID operation, long offset) {
        return offer(operation, offset, 10);
    }

    private InboxResult offer(UUID operation, long offset, int maximumAttempts) {
        return offerFor(TENANT, operation, offset, maximumAttempts);
    }

    private InboxResult offerFor(UUID tenantId, UUID operation, long offset, int maximumAttempts) {
        return executorAt(NOW, maximumAttempts)
                .execute(
                        ShipmentReconciliationHandler.CONSUMER_NAME,
                        operation.toString(),
                        body(tenantId, operation),
                        Map.of(),
                        TOPIC,
                        0,
                        offset);
    }

    private InboxExecutor executorAt(Instant at, int maximumAttempts) {
        return new InboxExecutor(
                new JdbcInboxStore(jdbc, Clock.fixed(at, ZoneOffset.UTC)),
                new InboxHandlerRegistry(List.of(handler)),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SimpleMeterRegistry(),
                event -> {},
                maximumAttempts);
    }

    private InboxRetryWorker workerAt(Instant at) {
        return new InboxRetryWorker(
                new JdbcInboxStore(jdbc, Clock.fixed(at, ZoneOffset.UTC)),
                executorAt(at, 10),
                new InboxHandlerRegistry(List.of(handler)),
                20);
    }

    /** The record exactly as {@code KafkaOutboxPublisher} writes it. */
    private static String body(UUID tenantId, UUID operation) {
        return """
                {"eventId":"%s","eventType":"ShipmentReconciliationRequested","eventVersion":1,
                 "tenantId":"%s","aggregateType":"DeliveryOperation","aggregateId":"%s",
                 "correlationId":"correlation-1","causationId":null,
                 "occurredAt":"2026-08-25T10:00:00Z",
                 "payload":{"operationCommandId":"%s","bindingId":"%s","brandId":"%s",
                            "locationId":null,"providerType":"scripted",
                            "capability":"CREATE_ON_DEMAND_SHIPMENT","externalReference":"ext-1",
                            "uncertainErrorCode":"READ_TIMEOUT"}}""".formatted(operation, tenantId, operation, operation, BINDING, BRAND);
    }

    private DeliveryProcessor processor() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        return new DeliveryProcessor(
                new DeliveryGateway(List.of(partner), lookup(), fixedResolver()),
                new DeliveryCircuitBreakers(meters, Clock.systemUTC()),
                meters,
                new RecordingReconciliationOutbox());
    }

    private long outboxRows() {
        return jdbc.sql("SELECT count(*) FROM integration.outbox_events")
                .query(Long.class)
                .single();
    }

    private String resolution(UUID operation) {
        return outboxField(operation, "resolution");
    }

    private String providerStatus(UUID operation) {
        return outboxField(operation, "providerStatus");
    }

    private String outboxField(UUID operation, String field) {
        return jdbc.sql("""
                SELECT payload ->> :field FROM integration.outbox_events
                 WHERE aggregate_id = :id AND event_type = 'ShipmentOutcomeReconciled'
                """)
                .param("field", field)
                .param("id", operation)
                .query(String.class)
                .single();
    }

    private String outboxTopic(UUID operation) {
        return jdbc.sql("""
                SELECT topic FROM integration.outbox_events WHERE aggregate_id = :id
                """).param("id", operation).query(String.class).single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-reconcile', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /**
     * Resolves the binding for {@link #TENANT} and for nobody else, which is what
     * makes the cross-tenant test a test of the production check rather than of
     * the fixture.
     */
    private static ProviderInstallationLookup lookup() {
        SecretReference reference =
                new SecretReference("local", SecretCategory.PROVIDER_DELIVERY, "tenant", "scripted");
        BindingRef binding =
                new BindingRef(BINDING, INSTALLATION, TENANT, ProviderCategory.DELIVERY, "scripted", BRAND, null);

        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(UUID t, UUID b, @Nullable UUID l, String code) {
                return Optional.empty();
            }

            @Override
            public List<BindingRef> candidateBindings(UUID tenantId, UUID b, @Nullable UUID l, String code) {
                return TENANT.equals(tenantId) ? List.of(binding) : List.of();
            }

            @Override
            public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
                return Optional.of(new InstallationSnapshot(
                        INSTALLATION,
                        ProviderCategory.DELIVERY,
                        "scripted",
                        "local",
                        "http://127.0.0.1:1",
                        "ACTIVE",
                        reference.toString(),
                        "v1"));
            }
        };
    }

    private static SecretResolver fixedResolver() {
        return new SecretResolver() {
            private final SecretValue value = SecretValue.of("token");

            @Override
            public SecretValue resolve(SecretReference r) {
                return value;
            }

            @Override
            public SecretValue resolveFresh(SecretReference r) {
                return value;
            }
        };
    }

    /** A courier partner that can be told what to answer, and counts what it was asked. */
    private static final class ScriptedPartner implements DeliveryPartner {

        private final List<String> idempotencyKeys = new java.util.ArrayList<>();
        private ProviderOutcome standing = ProviderOutcome.success(Map.of(), "ext-1");
        private Runnable observer = () -> {};
        private int queries;

        void onQuery(ProviderOutcome outcome) {
            this.standing = outcome;
        }

        void observe(Runnable observer) {
            this.observer = observer;
        }

        @Override
        public String providerType() {
            return "scripted";
        }

        @Override
        public Set<DeliveryCapability> capabilities() {
            return Set.of(DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT, DeliveryCapability.QUERY_SHIPMENT);
        }

        @Override
        public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
            return standing;
        }

        @Override
        public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
            return standing;
        }

        @Override
        public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
            return standing;
        }

        @Override
        public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
            return standing;
        }

        @Override
        public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
            return standing;
        }

        @Override
        public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
            queries++;
            idempotencyKeys.add(call.idempotencyKey());
            observer.run();
            return standing;
        }
    }
}
