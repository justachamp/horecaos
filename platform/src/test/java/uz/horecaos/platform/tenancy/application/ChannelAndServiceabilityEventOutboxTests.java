package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.TenancyOutboxEventListener;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * ADR 0036's channel and serviceability events, which V0175 is what gives them
 * a producer. Follows {@code TenantOutboxTransactionIntegrationTests}'s own
 * shape exactly: a minimal Spring context with a real transaction manager and a
 * real PostgreSQL, so that a row landing in {@code integration.outbox_events}
 * is proof the {@code @TransactionalEventListener(BEFORE_COMMIT)} fired inside
 * the same commit as the business write, not evidence manufactured by mocking
 * the publisher.
 *
 * <p>Every payload is additionally validated against its checked-in JSON
 * Schema, the way {@code EventSchemaValidationTests} validates the tenancy
 * events that existed before this class — ADR 0032's rule that a producer
 * serializes from a version-specific DTO and the output must validate against
 * the published schema is exercised here for the first time for these seven
 * events.
 */
class ChannelAndServiceabilityEventOutboxTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private AnnotationConfigApplicationContext context;
    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for outbox integration tests");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenant(TENANT, "channel-events-tenant");
        insertTenant(OTHER_TENANT, "channel-events-other-tenant");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    @Test
    @DisplayName("SalesChannelActivated lands in the outbox in the same transaction as the channel row")
    void salesChannelActivatedCommitsWithTheChannel() throws Exception {
        SalesChannelService channels = context.getBean(SalesChannelService.class);

        var channel = channels.create(
                TENANT,
                new SalesChannelService.CreateChannelCommand(
                        "KIOSK1", "KIOSK", "Front kiosk", null, false, true, null));

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.sales_channels WHERE id = :id")
                        .param("id", channel.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        Map<String, Object> event = singleOutboxEventFor(channel.id());
        assertThat(event).containsEntry("eventType", "SalesChannelActivated").containsEntry("tenantId", TENANT);
        assertThat(event).containsEntry("aggregateId", channel.id());
        assertThat(event).containsEntry("status", "PENDING");

        assertPayloadValidatesAgainstSchema("SalesChannelActivated", payloadOf(event));
    }

    @Test
    @DisplayName("SalesChannelArchived lands in the outbox when a channel is retired")
    void salesChannelArchivedCommitsOnArchive() throws Exception {
        SalesChannelService channels = context.getBean(SalesChannelService.class);
        var channel = channels.create(
                TENANT,
                new SalesChannelService.CreateChannelCommand("KIOSK2", "KIOSK", "Side kiosk", null, false, true, null));

        channels.archive(TENANT, channel.id(), channel.version());

        List<Map<String, Object>> events = outboxEventsFor(channel.id());
        assertThat(events)
                .extracting(row -> row.get("eventType"))
                .containsExactly("SalesChannelActivated", "SalesChannelArchived");

        Map<String, Object> archived = events.get(1);
        assertPayloadValidatesAgainstSchema("SalesChannelArchived", payloadOf(archived));
    }

    @Test
    @DisplayName("ChannelAvailabilityChanged lands in the outbox when the payment matrix is replaced")
    void channelAvailabilityChangedCommitsOnMatrixReplace() throws Exception {
        SalesChannelService channels = context.getBean(SalesChannelService.class);
        var channel = channels.create(
                TENANT,
                new SalesChannelService.CreateChannelCommand("KIOSK3", "KIOSK", "Back kiosk", null, false, true, null));

        // The FK V0175 adds means CASH must be registered before the matrix can
        // name it -- the exact fact ChannelPaymentMethodRegistryConstraintTests
        // asserts directly; this test only needs it satisfied, not re-proved.
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, 'CASH', 'CASH', 'OPERATOR', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();

        channels.replacePaymentMethods(TENANT, channel.id(), Map.of("CASH", true), channel.version());

        List<Map<String, Object>> events = outboxEventsFor(channel.id());
        assertThat(events)
                .extracting(row -> row.get("eventType"))
                .containsExactly("SalesChannelActivated", "ChannelAvailabilityChanged");

        assertPayloadValidatesAgainstSchema("ChannelAvailabilityChanged", payloadOf(events.get(1)));
        assertThat((String) events.get(1).get("payload")).contains("PAYMENT_METHODS");
    }

    @Test
    @DisplayName("LocationServiceStateChanged lands in the outbox in the same transaction as the audit fact")
    void locationServiceStateChangedCommitsWithTheAuditFact() throws Exception {
        ServiceScheduleService schedules = context.getBean(ServiceScheduleService.class);

        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_CLOSED, "FRYER_DOWN", "The fryer failed", null));

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("the audit fact ADR 0027 already required must still land alongside the new event")
                .isEqualTo(1);

        Map<String, Object> event = singleOutboxEventFor(LOCATION);
        assertThat(event).containsEntry("tenantId", TENANT).containsEntry("aggregateId", LOCATION);
        assertPayloadValidatesAgainstSchema("LocationServiceStateChanged", payloadOf(event));
        assertThat((String) event.get("payload")).contains("FORCE_CLOSED").contains("FRYER_DOWN");
    }

    @Test
    @DisplayName("each tenant's channel events stay scoped to that tenant")
    void tenantIsolation() {
        SalesChannelService channels = context.getBean(SalesChannelService.class);

        var mine = channels.create(
                TENANT, new SalesChannelService.CreateChannelCommand("ISO1", "WEB", "Mine", null, false, true, null));
        var theirs = channels.create(
                OTHER_TENANT,
                new SalesChannelService.CreateChannelCommand("ISO1", "WEB", "Theirs", null, false, true, null));

        Map<String, Object> mineEvent = singleOutboxEventFor(mine.id());
        Map<String, Object> theirsEvent = singleOutboxEventFor(theirs.id());

        assertThat(mineEvent).containsEntry("tenantId", TENANT);
        assertThat(theirsEvent).containsEntry("tenantId", OTHER_TENANT);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM integration.outbox_events
                        WHERE tenant_id = :t AND aggregate_id = :a
                        """)
                        .param("t", TENANT)
                        .param("a", theirs.id())
                        .query(Long.class)
                        .single())
                .as("the other tenant's channel event must not be reachable under this tenant's id")
                .isZero();
    }

    // -------------------------------------------------------------- assertions

    /** The row's {@code payload} column, which every row this suite reads always carries. */
    private static String payloadOf(Map<String, Object> row) {
        return Objects.requireNonNull((String) row.get("payload"), "outbox row carried no payload");
    }

    private void assertPayloadValidatesAgainstSchema(String eventType, String payloadJson) throws Exception {
        EventContract contract = EventCatalog.require(eventType, 1);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream schemaStream = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(schemaStream)
                    .as("schema %s must exist", contract.schemaPath())
                    .isNotNull();
            JsonSchema schema = factory.getSchema(
                    Objects.requireNonNull(schemaStream),
                    SchemaValidatorsConfig.builder().build());
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode payload = mapper.readTree(payloadJson);
            Set<ValidationMessage> errors = schema.validate(payload);
            assertThat(errors)
                    .as("payload of %s does not satisfy %s: %s", eventType, contract.schemaPath(), payload)
                    .isEmpty();
        }
    }

    private Map<String, Object> singleOutboxEventFor(UUID aggregateId) {
        List<Map<String, Object>> rows = outboxEventsFor(aggregateId);
        assertThat(rows).as("expected exactly one outbox row").hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> outboxEventsFor(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT event_type, tenant_id, aggregate_id, status, payload::text AS payload
                        FROM integration.outbox_events
                        WHERE aggregate_id = :aggregateId
                        ORDER BY created_at
                        """)
                .param("aggregateId", aggregateId)
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "eventType", resultSet.getString("event_type"),
                        "tenantId", resultSet.getObject("tenant_id"),
                        "aggregateId", resultSet.getObject("aggregate_id"),
                        "status", resultSet.getString("status"),
                        "payload", resultSet.getString("payload")))
                .list();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        private static @Nullable DataSource dataSource;

        @Bean
        DataSource dataSource() {
            return Objects.requireNonNull(dataSource, "setUp() must set the data source before the context refreshes");
        }

        @Bean
        JdbcClient jdbcClient(DataSource configuredDataSource) {
            return JdbcClient.create(configuredDataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource configuredDataSource) {
            return new DataSourceTransactionManager(configuredDataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T07:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        CurrentActor currentActor() {
            AuthenticatedActor actor =
                    new AuthenticatedActor(UUID.randomUUID().toString(), Set.of("tenant-admin"), Map.of());
            return () -> actor;
        }

        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return tools.jackson.databind.json.JsonMapper.builder()
                    .findAndAddModules()
                    .build();
        }

        @Bean
        JdbcOutboxStore jdbcOutboxStore(JdbcClient jdbc) {
            return new JdbcOutboxStore(jdbc);
        }

        @Bean
        TenancyOutboxEventListener tenancyOutboxEventListener(
                JdbcOutboxStore outbox, tools.jackson.databind.ObjectMapper objectMapper) {
            return new TenancyOutboxEventListener(outbox, objectMapper, "tenancy.events");
        }

        @Bean
        AuditRecorder auditRecorder(JdbcClient jdbc, tools.jackson.databind.ObjectMapper objectMapper) {
            return new JdbcAuditRecorder(jdbc, objectMapper);
        }

        @Bean
        JdbcSalesChannelStore jdbcSalesChannelStore(JdbcClient jdbc) {
            return new JdbcSalesChannelStore(jdbc);
        }

        @Bean
        JdbcServiceabilityStore jdbcServiceabilityStore(JdbcClient jdbc) {
            return new JdbcServiceabilityStore(jdbc);
        }

        @Bean
        SalesChannelService salesChannelService(
                JdbcSalesChannelStore store, Clock clock, ApplicationEventPublisher events) {
            return new SalesChannelService(store, clock, events);
        }

        @Bean
        ServiceScheduleService serviceScheduleService(
                JdbcServiceabilityStore store,
                AuditRecorder audit,
                CurrentActor currentActor,
                Clock clock,
                ApplicationEventPublisher events) {
            return new ServiceScheduleService(store, audit, currentActor, clock, events);
        }
    }
}
