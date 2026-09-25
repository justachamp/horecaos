package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.fulfillment.application.DeliveryPlanningService;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;
import uz.horecaos.platform.ordering.application.AggregatorOrderIntakeService;
import uz.horecaos.platform.ordering.application.CustomerAddressBook;
import uz.horecaos.platform.ordering.application.OrderFulfillmentProcess;
import uz.horecaos.platform.ordering.infrastructure.JdbcDeliveryOrderPort;
import uz.horecaos.platform.ordering.infrastructure.customer.JdbcCustomerAddressBook;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcAggregatorOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
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
    private JdbcDeliveryPlanStore planStore;
    private FieldProtection protection;
    private ObjectMapper objectMapper;
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
                    integration.installations, tenant.locations, tenant.brands, tenant.tenants,
                    customer.customer_accounts CASCADE
                """).update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SalesChannelLookupAndBindings seeded = seedTenancyChannelAndBinding();
        bindingId = seeded.bindingId();
        variantId = UUID.randomUUID();

        var channels = new JdbcSalesChannelStore(jdbc);
        var providerInstallations =
                new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        // Same translation OrderingMarketplaceBindingAdapter performs in production — this test
        // stays in the ordering package, so it cannot reach that package-private class directly.
        MarketplaceBindingLookup installations =
                (tenantId, installationId, brandId, locationId) -> providerInstallations
                        .bindingForInstallation(tenantId, installationId, brandId, locationId)
                        .map(BindingRef::bindingId);
        var orderStore = new JdbcOrderStore(jdbc);
        var aggregatorStore = new JdbcAggregatorOrderStore(jdbc);

        // Real envelope encryption over a throwaway key (row 1.3g's DELIVERY
        // snapshot), the same fixture CartCheckoutAndOrderTests uses — a stub
        // that decrypts whatever it just encrypted would prove nothing about
        // the ADR 0029 row binding.
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-kek")::get, clock),
                "local"));
        objectMapper = JsonMapper.builder().build();
        CustomerAddressBook addresses = new JdbcCustomerAddressBook(jdbc, protection, objectMapper);

        // The real production DeliveryOrderPort, not a fake: DELIVERY's own
        // test proves the snapshot this service writes is exactly what
        // fulfilment reads back to open a plan — the same seam
        // DeliveryPlanTrigger relies on for a native order.
        var deliveryOrderPort = new JdbcDeliveryOrderPort(jdbc, protection, objectMapper);
        planStore = new JdbcDeliveryPlanStore(jdbc);
        var jobStore = new JdbcSourcingJobStore(jdbc);
        var dispatchBranches = new JdbcDispatchBranchStore(jdbc);
        DeliveryPlanner deliveryPlanner = new DeliveryPlanningService(
                deliveryOrderPort, planStore, jobStore, dispatchBranches, unconfigured(), clock);
        var fulfillmentProcess =
                new OrderFulfillmentProcess(new JdbcOrderProcessStore(jdbc), deliveryPlanner, objectMapper);

        service = new AggregatorOrderIntakeService(
                channels,
                installations,
                orderStore,
                aggregatorStore,
                addresses,
                protection,
                objectMapper,
                deliveryPlanner,
                fulfillmentProcess,
                clock);
    }

    /** Nothing configured at any scope — DeliveryPlanningService's own defaults apply. */
    private static PolicyResolver unconfigured() {
        return new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };
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
                "operator-subject-7",
                null,
                null,
                null);

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
                "operator-subject-7",
                null,
                null,
                null);

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
                "operator-subject-7",
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    @Test
    @DisplayName("a subtotal that does not match the sum of the lines given is refused before anything is written")
    void refusesAMistypedSubtotal() {
        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-9001",
                // 2 * 25,000 = 50,000, but the operator typed the aggregator's subtotal as 45,000.
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 2, 25_000, null)),
                "UZS",
                45_000,
                0,
                0,
                45_000,
                "idem-aggregator-mistyped-subtotal",
                "operator-subject-7",
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    @Test
    @DisplayName("a subtotal that reconciles with the sum of several lines is accepted")
    void acceptsASubtotalThatReconcilesAcrossMultipleLines() {
        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-9002",
                List.of(
                        new AggregatorOrderIntakeService.Line(variantId, "Osh", 2, 25_000, null),
                        new AggregatorOrderIntakeService.Line(variantId, "Salad", 1, 10_000, null)),
                "UZS",
                60_000, // 2 * 25,000 + 1 * 10,000
                0,
                0,
                60_000,
                "idem-aggregator-reconciles",
                "operator-subject-7",
                null,
                null,
                null);

        var result = service.create(command);

        assertThat(result.replayed()).isFalse();
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
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent',
                    'ACTIVE', 0, :latitude, :longitude, 'MERCHANT_PIN')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                // Amir Temur square — row 1.3g's DELIVERY test needs a
                // located branch for DeliveryPlanningService to plan against.
                .param("latitude", 41.311081)
                .param("longitude", 69.240562)
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

    // ------------------------------------------------------------- row 1.3g (DELIVERY)

    @Test
    @DisplayName("DELIVERY reuses the New order screen's own structured destination and creates a delivery plan")
    void aDeliveryEntryCreatesADeliveryPlan() {
        UUID accountId = insertCustomer();
        // A house number in line1, on purpose: the assertion below on the
        // plan's own destinationLabel is this test's proof that row 3.1's
        // masking guarantee holds for a manual entry too, not just a native
        // order — see DeliveryDestinationTests for the masking rule itself.
        UUID addressId = insertAddress(accountId, "Amir Temur ko'chasi 12", 41.312, 69.241);

        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-DELIVERY-1",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 30_000, null)),
                "UZS",
                30_000,
                0,
                0,
                30_000,
                "idem-aggregator-delivery-1",
                "operator-subject-7",
                "DELIVERY",
                accountId,
                new AggregatorOrderIntakeService.Destination(addressId, "Dilnoza", "+998901112233", null));

        var result = service.create(command);
        assertThat(result.replayed()).isFalse();

        var row = jdbc.sql("SELECT fulfillment_mode, status, customer_account_id, guest_reference_hash "
                        + "FROM ordering.orders WHERE id = :id")
                .param("id", result.orderId())
                .query((rs, n) -> new Object[] {
                    rs.getString("fulfillment_mode"),
                    rs.getString("status"),
                    rs.getObject("customer_account_id", UUID.class),
                    rs.getString("guest_reference_hash")
                })
                .single();
        assertThat(row[0]).isEqualTo("DELIVERY");
        // CONFIRMED, not RECEIVED: the one status DeliveryPlanner#planFor sources from.
        assertThat(row[1]).isEqualTo("CONFIRMED");
        // ADR 0040 still holds: a manual entry matches no customer account,
        // even though this one resolved one of that customer's addresses.
        assertThat(row[2]).as("customer_account_id").isNull();
        assertThat(row[3]).as("guest_reference_hash").isNotNull();

        DeliveryPlan plan = planStore.findByOrder(TENANT, result.orderId()).orElseThrow();
        assertThat(plan.customerDeliveryFeeMinor()).isZero();
        // Row 3.1: the masked label reached the plan, and carries no house number.
        assertThat(plan.destinationLabel()).isNotNull();
        assertThat(plan.destinationLabel()).doesNotContain("12");
    }

    @Test
    @DisplayName("DELIVERY without a customer account or a destination is refused before anything is written")
    void deliveryWithoutADestinationIsRefused() {
        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-DELIVERY-2",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 30_000, null)),
                "UZS",
                30_000,
                0,
                0,
                30_000,
                "idem-aggregator-delivery-2",
                "operator-subject-7",
                "DELIVERY",
                null,
                null);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    @Test
    @DisplayName("a PICKUP entry carrying a customer account or a destination is refused")
    void pickupWithADestinationIsRefused() {
        UUID accountId = insertCustomer();
        UUID addressId = insertAddress(accountId, "Home 1", 41.312, 69.241);

        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-DELIVERY-3",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 30_000, null)),
                "UZS",
                30_000,
                0,
                0,
                30_000,
                "idem-aggregator-delivery-3",
                "operator-subject-7",
                null,
                accountId,
                new AggregatorOrderIntakeService.Destination(addressId, "Dilnoza", "+998901112233", null));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("DELIVERY against an address that is not this customer's own is refused, not silently served")
    void deliveryAgainstAWrongCustomersAddressIsRefused() {
        UUID accountId = insertCustomer();
        UUID otherAccountId = insertCustomer();
        UUID othersAddressId = insertAddress(otherAccountId, "Someone else's home", 41.312, 69.241);

        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-DELIVERY-4",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 30_000, null)),
                "UZS",
                30_000,
                0,
                0,
                30_000,
                "idem-aggregator-delivery-4",
                "operator-subject-7",
                "DELIVERY",
                accountId,
                new AggregatorOrderIntakeService.Destination(othersAddressId, "Dilnoza", "+998901112233", null));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    @Test
    @DisplayName("DELIVERY against an address with no coordinate is refused before anything is written")
    void deliveryAgainstAnUnlocatedAddressIsRefused() {
        UUID accountId = insertCustomer();
        UUID addressId = insertAddress(accountId, "Landmark only", null, null);

        var command = new AggregatorOrderIntakeService.Command(
                TENANT,
                BRAND,
                LOCATION,
                "UZUM-TEZKOR",
                "YE-DELIVERY-5",
                List.of(new AggregatorOrderIntakeService.Line(variantId, "Osh", 1, 30_000, null)),
                "UZS",
                30_000,
                0,
                0,
                30_000,
                "idem-aggregator-delivery-5",
                "operator-subject-7",
                "DELIVERY",
                accountId,
                new AggregatorOrderIntakeService.Destination(addressId, "Dilnoza", "+998901112233", null));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(ApiException.class);

        long orderCount = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
        assertThat(orderCount).isZero();
    }

    private UUID insertCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    /**
     * One of ADR 0015's addresses, written the way the customers module
     * writes it — the same shape {@code CartCheckoutAndOrderTests#insertAddress}
     * uses, so the encrypted-fields document this test's DELIVERY entry
     * decrypts is a real one, not a stub agreeing with itself.
     */
    private UUID insertAddress(UUID accountId, String line1, @Nullable Double latitude, @Nullable Double longitude) {
        UUID addressId = UUID.randomUUID();
        String document = objectMapper.writeValueAsString(Map.of(
                "line1", line1,
                "city", "Tashkent",
                "district", "Yunusobod"));
        String fields = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("customer.addresses", "encrypted_fields", addressId),
                        document)
                .serialize();

        jdbc.sql("""
                INSERT INTO customer.addresses (id, tenant_id, customer_account_id, label,
                    encrypted_fields, latitude, longitude, coordinate_source, status, version)
                VALUES (:id, :tenantId, :accountId, :label, :fields, :latitude, :longitude, :source,
                    'ACTIVE', 1)
                """)
                .param("id", addressId)
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .param("label", "Home")
                .param("fields", fields)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("source", latitude == null ? "LANDMARK_ONLY" : "CUSTOMER_PIN")
                .update();
        return addressId;
    }
}
