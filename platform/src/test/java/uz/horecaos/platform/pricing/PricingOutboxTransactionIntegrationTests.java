package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.PricingOutboxEventListener;
import uz.horecaos.platform.pricing.application.CatalogPricingContext;
import uz.horecaos.platform.pricing.application.PriceAuthoringService;
import uz.horecaos.platform.pricing.application.PriceAuthoringService.AssignmentScope;
import uz.horecaos.platform.pricing.application.PriceableType;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Proves the ADR 0027 audit fact and the ADR 0032 outbox row for {@code
 * PriceBookActivated} land in the same transaction as the activation itself —
 * the property {@link PriceAuthoringTests} cannot exercise, because it builds
 * {@link PriceAuthoringService} directly rather than as a Spring-managed bean,
 * so neither {@code @Transactional} nor {@code
 * PricingOutboxEventListener}'s own {@code @TransactionalEventListener} ever
 * runs there. Modelled on {@code TenantOutboxTransactionIntegrationTests}.
 */
class PricingOutboxTransactionIntegrationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-08T09:00:00Z");

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private AnnotationConfigApplicationContext context;
    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this integration test");
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
        jdbc.sql("TRUNCATE TABLE pricing.prices, pricing.price_book_assignments, pricing.price_books CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();

        seedTenancyAndCatalog();

        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void commitsTheAuditFactAndTheOutboxEventInTheActivationTransaction() {
        PriceAuthoringService authoring = context.getBean(PriceAuthoringService.class);

        var drafted = authoring.create(
                TENANT, BRAND, new PriceAuthoringService.NewPriceBook("Main menu", "UZS", null, null, 0));
        authoring.assign(
                TENANT,
                BRAND,
                drafted.id(),
                AssignmentScope.BRAND,
                null,
                new PriceAuthoringService.Assignment(0, null, null));
        var priced = authoring.setPrice(TENANT, BRAND, drafted.id(), PriceableType.VARIANT, VARIANT, 50_000L);

        authoring.activate(TENANT, BRAND, drafted.id(), priced.version());

        assertThat(jdbc.sql("SELECT status FROM pricing.price_books WHERE id = :id")
                        .param("id", drafted.id())
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");

        assertThat(jdbc.sql("""
                        SELECT action_code, target_id, target_version, capability_used
                        FROM audit.audit_events
                        WHERE target_type = 'PriceBook'
                        """)
                        .query((resultSet, rowNumber) -> Map.of(
                                "actionCode", resultSet.getString("action_code"),
                                "targetId", resultSet.getObject("target_id"),
                                "targetVersion", resultSet.getLong("target_version"),
                                "capability", resultSet.getString("capability_used")))
                        .single())
                .containsEntry("actionCode", "pricing.price_book.activated")
                .containsEntry("targetId", drafted.id())
                .containsEntry("capability", "pricing.activate");

        assertThat(jdbc.sql("""
                        SELECT event_type, tenant_id, aggregate_id, aggregate_type, status,
                               payload->>'priceBookId' AS price_book_id,
                               payload->>'brandId' AS brand_id,
                               payload->>'version' AS version,
                               payload->>'currency' AS currency
                        FROM integration.outbox_events
                        WHERE aggregate_id = :id
                        """)
                        .param("id", drafted.id())
                        .query((resultSet, rowNumber) -> Map.of(
                                "eventType", resultSet.getString("event_type"),
                                "tenantId", resultSet.getObject("tenant_id"),
                                "aggregateId", resultSet.getObject("aggregate_id"),
                                "aggregateType", resultSet.getString("aggregate_type"),
                                "status", resultSet.getString("status"),
                                "priceBookId", resultSet.getString("price_book_id"),
                                "brandId", resultSet.getString("brand_id"),
                                "version", resultSet.getString("version"),
                                "currency", resultSet.getString("currency")))
                        .single())
                .containsEntry("eventType", "PriceBookActivated")
                .containsEntry("tenantId", TENANT)
                .containsEntry("aggregateId", drafted.id())
                .containsEntry("aggregateType", "PriceBook")
                .containsEntry("status", "PENDING")
                .containsEntry("priceBookId", drafted.id().toString())
                .containsEntry("brandId", BRAND.toString())
                .containsEntry("currency", "UZS");
    }

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, 'pricing-outbox-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                                'ACTIVE', 0)
                        """).param("id", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                        """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                        VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                        """)
                .param("id", PRODUCT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                        INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                        VALUES (:id, :tenantId, :brandId, :productId, 'SKU-BURGER', 'ACTIVE')
                        """)
                .param("id", VARIANT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", PRODUCT)
                .update();
    }

    @Configuration(proxyBeanMethods = false)
    // proxyTargetClass matches Spring Boot's production default (CGLIB) and is
    // what makes @Transactional on PriceAuthoringService actually wrap calls in
    // a real transaction here, which is the entire point of this suite.
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
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        CurrentActor currentActor() {
            AuthenticatedActor actor = new AuthenticatedActor("pricing-outbox-test", Set.of("tenant-owner"), Map.of());
            return () -> actor;
        }

        @Bean
        JdbcPricingStore jdbcPricingStore(JdbcClient jdbc) {
            return new JdbcPricingStore(jdbc, JsonMapper.builder().build());
        }

        @Bean
        CatalogPricingContext catalogPricingContext(JdbcClient jdbc) {
            return new JdbcCatalogPricingContext(jdbc, "uz");
        }

        @Bean
        JdbcSalesChannelStore salesChannelStore(JdbcClient jdbc) {
            return new JdbcSalesChannelStore(jdbc);
        }

        @Bean
        JdbcOutboxStore jdbcOutboxStore(JdbcClient jdbc) {
            return new JdbcOutboxStore(jdbc);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        JdbcAuditRecorder auditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
            return new JdbcAuditRecorder(jdbc, objectMapper);
        }

        @Bean
        PricingOutboxEventListener pricingOutboxEventListener(JdbcOutboxStore outbox, ObjectMapper objectMapper) {
            return new PricingOutboxEventListener(outbox, objectMapper, "pricing.events");
        }

        @Bean
        PriceAuthoringService priceAuthoringService(
                JdbcPricingStore store,
                CatalogPricingContext catalog,
                JdbcSalesChannelStore channels,
                Clock clock,
                JdbcAuditRecorder audit,
                ApplicationEventPublisher events,
                CurrentActor currentActor) {
            return new PriceAuthoringService(store, catalog, channels, clock, audit, events, currentActor);
        }
    }
}
