package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.application.EnforcementCeiling;
import uz.horecaos.platform.commercial.application.EntitlementQueryService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@code CatalogAuthoringService#createProduct} is ADR 0021's one enforced
 * quantity limit this wave: {@code catalog.products.max_count} is metered on
 * every product and — once a plan sets a real limit under {@code HARD} — refused
 * before the row is written, the same "before mutation" shape {@code
 * CampaignService#start} already demonstrates for a feature gate.
 *
 * <p>Full stack against PostgreSQL, the way {@code CommercialPlatformTests}
 * proves the port itself: a real plan, a real subscription, a real ceiling, and
 * the real {@code CatalogAuthoringService} a controller actually calls — not the
 * entitlement service in isolation.
 */
class CatalogAuthoringEntitlementTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");
    private static final String AUTHOR = "catalog-entitlement.author";
    private static final String APPROVER = "catalog-entitlement.approver";
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private PlanCatalogService plans;
    private SubscriptionService subscriptions;
    private UUID catalogId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this integration test");
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

        jdbc.sql("TRUNCATE TABLE catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        // commercial.plans is platform-wide, not tenant-scoped, so it survives a
        // tenant truncate — CommercialPlatformTests clears it the same way, for
        // the same reason: a fixed plan code reused across @Test methods would
        // otherwise collide on the second one.
        jdbc.sql("TRUNCATE TABLE commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = 'commercial.enforcement_ceiling'")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'catalog-entitlement-tenant', 'Tenant', 'Tenant', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());

        JdbcPlanStore planStore = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptionStore = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usageStore =
                new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        EnforcementCeiling ceiling = new EnforcementCeiling(new JdbcConfigurationResolver(jdbc));
        EntitlementQueryService entitlements =
                new EntitlementQueryService(subscriptionStore, planStore, usageStore, ceiling, clock);
        UsageMeteringService metering = new UsageMeteringService(usageStore, entitlements, clock);
        plans = new PlanCatalogService(planStore, audit, clock);
        subscriptions = new SubscriptionService(subscriptionStore, planStore, entitlements, audit, clock);

        authoring = new CatalogAuthoringService(store, audit, entitlements, metering, clock);
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN-MENU", "Main menu", "uz");
    }

    @Test
    @DisplayName("every created product meters one against catalog.products.max_count")
    void everyProductMetersOne() {
        createProduct("BURGER-1");
        createProduct("BURGER-2");

        assertThat(consumed()).isEqualTo(2);
    }

    @Test
    @DisplayName("a HARD-limited plan refuses the product that would exceed it")
    void aHardLimitRefusesTheOverage() {
        activateAndSubscribeHardLimit(2);

        createProduct("BURGER-1");
        createProduct("BURGER-2");

        assertThatThrownBy(() -> createProduct("BURGER-3"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException failure = (ApiException) thrown;
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_REQUIRED);
                    assertThat(failure.properties())
                            .containsEntry("limit", 2L)
                            .containsEntry("consumed", 2L)
                            .containsKey("upgradePath");
                });

        assertThat(consumed())
                .as("the refused product is never inserted, so the meter still reads what was " + "actually created")
                .isEqualTo(2);
        assertThat(store.productsInCatalog(TENANT, BRAND, catalogId))
                .as("only the two products that were actually created exist")
                .hasSize(2);
    }

    @Test
    @DisplayName("a meter-only tenant is never refused, even over its plan's limit")
    void meterOnlyNeverRefuses() {
        UUID versionId = activateHardLimitPlan(1);
        subscriptions.start(TENANT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        // No ceiling raised: METER_ONLY is the default for every tenant.

        createProduct("BURGER-1");
        createProduct("BURGER-2");
        createProduct("BURGER-3");

        assertThat(consumed())
                .as("measured past the limit, but never refused, until the ceiling is raised")
                .isEqualTo(3);
    }

    private void createProduct(String code) {
        authoring.createProduct(TENANT, BRAND, catalogId, code, code, null, "uz", null, null, UNCLASSIFIED, null);
    }

    private void activateAndSubscribeHardLimit(int limit) {
        UUID versionId = activateHardLimitPlan(limit);
        subscriptions.start(TENANT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        setCeiling(TENANT, "HARD");
    }

    private UUID activateHardLimitPlan(int limit) {
        UUID planId = plans.createPlan("BASIC", "Basic", ActorRef.user(AUTHOR, null), "the price list", "corr");
        UUID versionId = plans.draftVersion(
                planId,
                "UZS",
                1_200_000,
                "MONTHLY",
                null,
                Map.of(
                        EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT.code(),
                        PlanEntitlement.counted(
                                EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT.code(),
                                limit,
                                EnforcementMode.HARD,
                                ResetPeriod.NONE,
                                null,
                                null)),
                ActorRef.user(AUTHOR, null),
                "the 2026 price list",
                "corr");
        plans.activate(versionId, ActorRef.user(APPROVER, null), "signed off by finance", "corr");
        return versionId;
    }

    private void setCeiling(UUID tenantId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (
                    id, key_code, scope_type, tenant_id, value_type, string_value, set_by, reason)
                VALUES (:id, 'commercial.enforcement_ceiling', 'TENANT', :tenantId, 'STRING',
                    :value, 'test', 'staged rollout')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("value", mode)
                .update();
    }

    private long consumed() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(consumed_quantity), 0) FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = 'catalog.products.max_count'
                   AND period_key = 'LIFETIME'
                """).param("tenantId", TENANT).query(Long.class).single();
    }
}
