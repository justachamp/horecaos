package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.ordering.application.AggregatorOrderIntakeService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcAggregatorOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Wave P14, row {@code 1.3g} (ADR 0040): a manually keyed aggregator order
 * writes every one of the four V0038 authority columns {@code
 * JdbcMarketplaceOrderIntake} writes for an automated partner push — {@code
 * origin}, {@code pricing_authority}, {@code entry_mode} and {@code
 * marketplace_binding_id} — with {@code entry_mode = MANUAL} in place of
 * {@code API}, so a phoned-through order never lands as an ordinary
 * own-channel sale.
 *
 * <p>Runs against real Postgres and the production {@link
 * uz.horecaos.platform.tenancy.api.SalesChannelLookup}/{@link
 * uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup}
 * adapters, over the identical installation/binding/channel fixture shape
 * {@code MarketplaceChannelTests} already proves the automated intake path
 * against — the same tables, the same category, the same {@code
 * AGGREGATOR}-system-type channel with a {@code provider_installation_id}.
 */
class AggregatorOrderIntakeServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AggregatorOrderIntakeService service;
    private UUID bindingId;
    private UUID variantId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("""
                TRUNCATE TABLE ordering.orders, tenant.sales_channels, integration.bindings,
                    integration.installations, tenant.locations, tenant.brands, tenant.tenants CASCADE
                """).update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SalesChannelLookupAndBindings seeded = seedTenancyChannelAndBinding();
        bindingId = seeded.bindingId();
        variantId = UUID.randomUUID();

        var channels = new JdbcSalesChannelStore(jdbc);
        var installations = new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        var orderStore = new JdbcOrderStore(jdbc);
        var aggregatorStore = new JdbcAggregatorOrderStore(jdbc);
        service = new AggregatorOrderIntakeService(channels, installations, orderStore, aggregatorStore, clock);
    }

    @Test
    @DisplayName("a manual aggregator order writes all four V0038 authority columns")
    void writesAllFourAuthorityColumns() {
        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-2291-04",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 2, 25_000, "sku-osh")),
                "UZS",
                50_000,
                0,
                0,
                50_000,
                "idem-aggregator-1",
                "operator-subject-7");

        var result = service.create(command);

        assertThat(result.replayed()).isFalse();

        var row = jdbc.sql("""
                        SELECT origin, pricing_authority, entry_mode, marketplace_binding_id,
                               fulfillment_mode, created_by_actor_type, created_by_actor_id,
                               total_minor, guest_reference_hash, customer_account_id
                          FROM ordering.orders WHERE id = :id
                        """)
                .param("id", result.orderId())
                .query((rs, n) -> new Object[] {
                    rs.getString("origin"),
                    rs.getString("pricing_authority"),
                    rs.getString("entry_mode"),
                    rs.getObject("marketplace_binding_id", UUID.class),
                    rs.getString("fulfillment_mode"),
                    rs.getString("created_by_actor_type"),
                    rs.getString("created_by_actor_id"),
                    rs.getLong("total_minor"),
                    rs.getString("guest_reference_hash"),
                    rs.getObject("customer_account_id", UUID.class)
                })
                .single();

        assertThat(row[0]).isEqualTo("MARKETPLACE");
        assertThat(row[1]).isEqualTo("EXTERNAL");
        assertThat(row[2]).isEqualTo("MANUAL");
        assertThat(row[3]).isEqualTo(bindingId);
        assertThat(row[4]).isEqualTo("PICKUP");
        assertThat(row[5]).isEqualTo("USER");
        assertThat(row[6]).isEqualTo("operator-subject-7");
        assertThat(row[7]).isEqualTo(50_000L);
        // ck_order_owner: exactly one of customer_account_id/guest_reference_hash is set.
        assertThat(row[8]).isNotNull();
        assertThat(row[9]).isNull();

        long lineCount = jdbc.sql("SELECT count(*) FROM ordering.order_lines WHERE order_id = :id")
                .param("id", result.orderId())
                .query(Long.class)
                .single();
        assertThat(lineCount).isEqualTo(1);

        String referenceValue = jdbc.sql(
                        "SELECT reference_value FROM ordering.order_external_references WHERE order_id = :id")
                .param("id", result.orderId())
                .query(String.class)
                .single();
        assertThat(referenceValue).isEqualTo("YE-2291-04");
    }

    @Test
    @DisplayName("resubmitting the same Idempotency-Key replays the original order rather than writing a second one")
    void resubmissionReplays() {
        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-2291-05",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 25_000, null)),
                "UZS",
                25_000,
                0,
                0,
                25_000,
                "idem-aggregator-replay",
                "operator-subject-7");

        var first = service.create(command);
        var second = service.create(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("a channel that is not AGGREGATOR-typed is refused before anything is written")
    void refusesANonAggregatorChannel() {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'CALL-CENTRE', 'CALL_CENTRE', 'Call centre', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();

        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "call-centre",
                "YE-1",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 25_000, null)),
                "UZS",
                25_000,
                0,
                0,
                25_000,
                "idem-wrong-channel",
                "operator-subject-7");

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private record SalesChannelLookupAndBindings(UUID bindingId) {}

    private SalesChannelLookupAndBindings seedTenancyChannelAndBinding() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'aggregator-intake-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('uzum-sandbox-p14', 'MARKETPLACE', 'UZUM_TEZKOR',
                        'https://sandbox.example.uz', false, 'sandbox.example.uz')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status)
                VALUES (:id, :tenantId, 'MARKETPLACE', 'UZUM_TEZKOR', 'uzum-sandbox-p14',
                        'Uzum Tezkor', 'ACTIVE')
                """).param("id", installationId).param("tenantId", TENANT).update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, location_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', :now)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status, externally_priced,
                    provider_installation_id)
                VALUES (:id, :tenantId, 'UZUM-TEZKOR', 'AGGREGATOR', 'Uzum Tezkor', 'ACTIVE', true,
                        :installationId)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .update();

        return new SalesChannelLookupAndBindings(bindingId);
    }
}
