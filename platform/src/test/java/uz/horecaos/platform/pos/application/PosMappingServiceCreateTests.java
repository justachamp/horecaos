package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.application.PosMappingService.CreateOutcome;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave P24, second-pass adversarial review: before this wave {@code
 * PosMappingService.create} never confirmed {@code horecaosEntityId} names a
 * real row of the given type, let alone one owned by this tenant/brand —
 * {@code horecaos_entity_id} is deliberately FK-less (V0013, polymorphic
 * across six schemas), so an operator (or a client bug) naming a garbage or
 * cross-tenant id got a 200 and an {@code ACTIVE} mapping created anyway.
 */
class PosMappingServiceCreateTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127004");

    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127101");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899e-7b1c-a8cf-0242ac127102");

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private PosMappingService service;

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
        jdbc = JdbcClient.create(db.dataSource());

        jdbc.sql("DELETE FROM integration.provider_entity_mappings WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM payments.payment_methods WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.products WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:t, :other)")
                .param("t", TENANT)
                .param("other", OTHER_TENANT)
                .update();

        insertTenant(TENANT, "pos-create-tenant");
        insertTenant(OTHER_TENANT, "pos-create-other-tenant");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'CREATE_BRAND', 'create-brand', 'Create brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'OTHER_BRAND', 'other-brand', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', :providerType, 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """)
                .param("id", INSTALLATION)
                .param("t", TENANT)
                .param("providerType", FakePosAdapter.PROVIDER_TYPE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        JdbcPosMappingStore mappingStore = new JdbcPosMappingStore(jdbc);
        JdbcPosBindingConfiguration configuration =
                new JdbcPosBindingConfiguration(jdbc, JsonMapper.builder().build());
        PosAdapterRegistry registry = new PosAdapterRegistry(List.of(new FakePosAdapter()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PosMappingService(mappingStore, configuration, registry, clock);
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'POS create', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("slug", slug)
                .update();
    }

    private UUID insertPaymentMethod(UUID tenantId, String code, String displayName) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility)
                VALUES (:id, :t, :code, :displayName, 'TERMINAL')
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("code", code)
                .param("displayName", displayName)
                .update();
        return id;
    }

    private UUID insertProduct(UUID tenantId, UUID brandId, String code) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :t, :b, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .update();
        return id;
    }

    @Test
    @DisplayName("creating a mapping for a horecaosEntityId that does not exist at all is refused, not created")
    void createRefusesANonexistentHorecaosEntity() {
        CreateOutcome outcome = service.create(
                TENANT, BINDING, MappingEntityType.PAYMENT_TYPE, UUID.randomUUID(), "ext-nonexistent", null);

        assertThat(outcome.kind()).isEqualTo(CreateOutcome.Kind.NOT_FOUND);
        assertThat(mappingCount()).isZero();
    }

    @Test
    @DisplayName("creating a mapping for a horecaosEntityId that belongs to a different tenant is refused")
    void createRefusesACrossTenantHorecaosEntity() {
        UUID othersMethod = insertPaymentMethod(OTHER_TENANT, "CASH", "Cash");

        CreateOutcome outcome =
                service.create(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE, othersMethod, "ext-cross-tenant", null);

        assertThat(outcome.kind()).isEqualTo(CreateOutcome.Kind.NOT_FOUND);
        assertThat(mappingCount()).isZero();
    }

    @Test
    @DisplayName("creating a PRODUCT mapping for a product that belongs to a different brand of the same tenant "
            + "is refused")
    void createRefusesACrossBrandProduct() {
        UUID othersProduct = insertProduct(TENANT, OTHER_BRAND, "BURGER");

        CreateOutcome outcome =
                service.create(TENANT, BINDING, MappingEntityType.PRODUCT, othersProduct, "ext-cross-brand", null);

        assertThat(outcome.kind()).isEqualTo(CreateOutcome.Kind.NOT_FOUND);
        assertThat(mappingCount()).isZero();
    }

    @Test
    @DisplayName("creating a mapping for a horecaosEntityId that genuinely exists in this tenant still succeeds")
    void createSucceedsForARealHorecaosEntity() {
        UUID cash = insertPaymentMethod(TENANT, "CASH", "Cash");

        CreateOutcome outcome = service.create(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE, cash, "ext-cash", null);

        assertThat(outcome.kind()).isEqualTo(CreateOutcome.Kind.CREATED);
        assertThat(mappingCount()).isEqualTo(1);
    }

    private long mappingCount() {
        return jdbc.sql("SELECT count(*) FROM integration.provider_entity_mappings WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }
}
