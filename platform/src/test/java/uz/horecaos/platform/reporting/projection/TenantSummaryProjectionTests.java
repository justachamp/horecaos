package uz.horecaos.platform.reporting.projection;

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
import uz.horecaos.platform.integration.inbox.EnvelopeValidator;
import uz.horecaos.platform.integration.inbox.InboxExecutor;
import uz.horecaos.platform.integration.inbox.InboxHandlerRegistry;
import uz.horecaos.platform.integration.inbox.InboxResult;
import uz.horecaos.platform.integration.inbox.JdbcInboxStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The platform's first real consumer (ADR 0005).
 *
 * <p>A projection is only useful if it is safe to rebuild and safe to receive
 * twice, so both are asserted rather than assumed.
 */
class TenantSummaryProjectionTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121001");
    private static final UUID BRAND_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121002");
    private static final UUID BRAND_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121003");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121004");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InboxExecutor executor;

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
        jdbc.sql("TRUNCATE TABLE reporting.tenant_summaries").update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();

        Clock clock = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        executor = new InboxExecutor(
                new JdbcInboxStore(jdbc, clock),
                new InboxHandlerRegistry(List.of(
                        new TenantSummaryProjection.TenantCreatedProjection(jdbc),
                        new TenantSummaryProjection.BrandCreatedProjection(jdbc),
                        new TenantSummaryProjection.LocationCreatedProjection(jdbc))),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SimpleMeterRegistry(),
                10);
    }

    @Test
    void buildsATenantSummaryFromTheThreeTenancyEvents() {
        offer(tenantCreated(), TENANT, 0);
        offer(brandCreated(BRAND_A), BRAND_A, 1);
        offer(brandCreated(BRAND_B), BRAND_B, 2);
        offer(locationCreated(), LOCATION, 3);

        Map<String, Object> summary = summary();

        assertThat(summary)
                .containsEntry("slug", "acme")
                .containsEntry("status", "PROVISIONING")
                .containsEntry("customer_identity_mode", "TENANT_SHARED")
                .containsEntry("brand_count", 2)
                .containsEntry("location_count", 1);
    }

    @Test
    void aRedeliveredEventDoesNotDoubleCount() {
        offer(tenantCreated(), TENANT, 0);
        String brand = brandCreated(BRAND_A);

        assertThat(offer(brand, BRAND_A, 1)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(brand, BRAND_A, 2)).isEqualTo(InboxResult.DUPLICATE_IGNORED);
        assertThat(offer(brand, BRAND_A, 3)).isEqualTo(InboxResult.DUPLICATE_IGNORED);

        assertThat(summary())
                .as("a projection that double-counts on redelivery is worse than no projection")
                .containsEntry("brand_count", 1);
    }

    @Test
    void aBrandArrivingBeforeItsTenantStillCounts() {
        offer(brandCreated(BRAND_A), BRAND_A, 0);

        assertThat(summary())
                .as("events cross partitions, so the projection must tolerate arriving out of order")
                .containsEntry("brand_count", 1)
                .containsEntry("status", "UNKNOWN");

        offer(tenantCreated(), TENANT, 1);

        assertThat(summary()).containsEntry("status", "PROVISIONING").containsEntry("brand_count", 1);
    }

    @Test
    void theProjectionCanBeRebuiltFromScratch() {
        offer(tenantCreated(), TENANT, 0);
        offer(brandCreated(BRAND_A), BRAND_A, 1);

        // A projection is never an authority: dropping it must be a rebuild,
        // not a data loss.
        jdbc.sql("TRUNCATE TABLE reporting.tenant_summaries").update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();

        offer(tenantCreated(), TENANT, 0);
        offer(brandCreated(BRAND_A), BRAND_A, 1);

        assertThat(summary()).containsEntry("slug", "acme").containsEntry("brand_count", 1);
    }

    @Test
    void theProjectionCommitsWithTheInboxTransition() {
        offer(tenantCreated(), TENANT, 0);

        assertThat(jdbc.sql("""
                SELECT status FROM integration.inbox_messages
                 WHERE consumer_name = :consumer
                """)
                        .param("consumer", TenantSummaryProjection.CONSUMER_NAME)
                        .query(String.class)
                        .single())
                .isEqualTo("PROCESSED");
        assertThat(summary()).isNotEmpty();
    }

    private InboxResult offer(String body, UUID aggregateId, long offset) {
        return executor.execute(
                TenantSummaryProjection.CONSUMER_NAME,
                aggregateId.toString(),
                body,
                Map.of(),
                "tenancy.events",
                0,
                offset);
    }

    private Map<String, Object> summary() {
        return jdbc.sql("SELECT * FROM reporting.tenant_summaries WHERE tenant_id = :id")
                .param("id", TENANT)
                .query()
                .singleRow();
    }

    private static String tenantCreated() {
        return envelope(UUID.randomUUID(), "TenantCreated", "Tenant", TENANT, """
                {"tenantId":"%s","slug":"acme","legalName":"Acme Foods LLC","displayName":"Acme",
                 "defaultCurrency":"UZS","defaultTimezone":"Asia/Tashkent",
                 "status":"PROVISIONING","customerIdentityMode":"TENANT_SHARED"}""".formatted(TENANT));
    }

    private static String brandCreated(UUID brandId) {
        return envelope(UUID.randomUUID(), "BrandCreated", "Brand", brandId, """
                {"brandId":"%s","code":"ACME","slug":"acme-brand","displayName":"Acme","status":"ACTIVE"}""".formatted(brandId));
    }

    private static String locationCreated() {
        return envelope(UUID.randomUUID(), "LocationCreated", "Location", LOCATION, """
                {"locationId":"%s","brandId":"%s","code":"LOC","slug":"loc","displayName":"Chilonzor",
                 "timezone":"Asia/Tashkent","status":"ACTIVE"}""".formatted(LOCATION, BRAND_A));
    }

    private static String envelope(
            UUID eventId, String eventType, String aggregateType, UUID aggregateId, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"tenantId":"%s",
                 "aggregateType":"%s","aggregateId":"%s","correlationId":"correlation-1",
                 "causationId":null,"occurredAt":"2026-08-20T09:00:00Z","payload":%s}""".formatted(eventId, eventType, TENANT, aggregateType, aggregateId, payload);
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'acme', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }
}
