package uz.horecaos.platform.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.application.DeliveryPlanningService;
import uz.horecaos.platform.fulfillment.application.SourcingJournal;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySubsidyBearer;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The order-to-fulfilment seam's own read (ADR 0014, gap map rows
 * 1.2e/1.2n/2.1a), against a real PostgreSQL — the FK from {@code
 * fulfillment.shipments.provider_binding_id} to a real {@code
 * integration.bindings} row and the {@code delivery_cost_subsidies} arithmetic
 * constraint are the database's own, not something a mock stands in for.
 * Setup mirrors {@code ManualDispatchServiceTests} for the plan and {@code
 * DeliverySourcingTests} for the provider binding.
 */
class OrderDeliveryControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant CONFIRMED = Instant.parse("2026-09-01T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcDeliveryPlanStore planStore;
    private JdbcAssignmentStore assignments;
    private JdbcDeliveryCostSubsidyStore subsidies;
    private OrderDeliveryController controller;
    private DeliveryPlanningService planning;
    private UUID branch;
    private UUID siblingBranch;
    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for order delivery tests");
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
                TRUNCATE TABLE
                    fulfillment.delivery_cost_subsidies,
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    fulfillment.delivery_sourcing_jobs,
                    fulfillment.couriers,
                    fulfillment.courier_types,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    integration.bindings,
                    integration.installations,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CONFIRMED, ZoneOffset.UTC);
        planStore = new JdbcDeliveryPlanStore(jdbc);
        assignments = new JdbcAssignmentStore(jdbc);
        subsidies = new JdbcDeliveryCostSubsidyStore(jdbc);
        controller = new OrderDeliveryController(planStore, assignments, subsidies);

        seedTenancy();
        planning = new DeliveryPlanningService(
                new SingleOrder(),
                planStore,
                new JdbcSourcingJobStore(jdbc),
                new JdbcDispatchBranchStore(jdbc),
                unconfigured(),
                clock);
    }

    @Test
    @DisplayName("the order-keyed read returns both delivery money figures and the shipment's custody timestamps")
    void returnsBothMoneyFiguresAndShipmentTimestamps() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        UUID shipmentId = UUID.randomUUID();
        Instant assignedAt = CONFIRMED.plusSeconds(60);
        Instant pickedUpAt = CONFIRMED.plusSeconds(600);
        Instant deliveredAt = CONFIRMED.plusSeconds(1_800);
        insertShipment(
                shipmentId, plan, "PARTNER", null, bindingId, "yandex-delivery", assignedAt, pickedUpAt, deliveredAt);

        long providerCostMinor = plan.customerDeliveryFeeMinor() + 3_000L;
        subsidies.record(
                new SourcingJournal.CostSubsidy(
                        TENANT,
                        BRAND,
                        branch,
                        plan.id(),
                        shipmentId,
                        bindingId,
                        "yandex-delivery",
                        plan.customerDeliveryFeeMinor(),
                        providerCostMinor,
                        providerCostMinor - plan.customerDeliveryFeeMinor(),
                        plan.currency(),
                        DeliverySubsidyBearer.PLATFORM,
                        null,
                        0,
                        CONFIRMED),
                "test-fixture");

        Instant courierEtaAt = CONFIRMED.plusSeconds(1_500);
        planStore.updateCourierEta(TENANT, plan.id(), courierEtaAt);

        ResponseEntity<OrderDeliveryController.OrderDeliveryResponse> response =
                controller.delivery(TENANT, BRAND, branch, plan.orderId());

        OrderDeliveryController.OrderDeliveryResponse body = Objects.requireNonNull(response.getBody());
        OrderDeliveryController.ShipmentResponse shipment = Objects.requireNonNull(body.shipment());
        assertThat(body.planId()).isEqualTo(plan.id());
        assertThat(body.customerDeliveryFeeMinor()).isEqualTo(plan.customerDeliveryFeeMinor());
        assertThat(body.providerCostMinor()).isEqualTo(providerCostMinor);
        assertThat(body.courierEtaAt()).isEqualTo(courierEtaAt);
        assertThat(shipment.sourceType()).isEqualTo("PARTNER");
        assertThat(shipment.assignedAt()).isEqualTo(assignedAt);
        assertThat(shipment.pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(shipment.deliveredAt()).isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("an in-house courier's shipment carries no provider-billed figure -- honestly absent, not zero")
    void anInternalCourierShipmentCarriesNoProviderCost() {
        DeliveryPlan plan = openPlan();
        UUID courierId = seedCourier();
        insertShipment(
                UUID.randomUUID(), plan, "INTERNAL", courierId, null, null, CONFIRMED.plusSeconds(30), null, null);

        ResponseEntity<OrderDeliveryController.OrderDeliveryResponse> response =
                controller.delivery(TENANT, BRAND, branch, plan.orderId());

        OrderDeliveryController.OrderDeliveryResponse body = Objects.requireNonNull(response.getBody());
        assertThat(Objects.requireNonNull(body.shipment()).courierId()).isEqualTo(courierId);
        assertThat(body.providerCostMinor())
                .as("no fulfillment.delivery_cost_subsidies row exists for an in-house courier, "
                        + "and this read must say so rather than fabricate a figure")
                .isNull();
    }

    @Test
    @DisplayName("an order with no delivery plan at all is not found -- pickup and dine-in have none")
    void anOrderWithNoDeliveryPlanIsNotFound() {
        Throwable refusal = catchThrowable(() -> controller.delivery(TENANT, BRAND, branch, UUID.randomUUID()));

        assertThat(refusal).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a plan at a sibling location is not found, not leaked across the location boundary")
    void aPlanAtASiblingLocationIsNotFound() {
        DeliveryPlan plan = openPlan();

        Throwable refusal = catchThrowable(() -> controller.delivery(TENANT, BRAND, siblingBranch, plan.orderId()));

        assertThat(refusal).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a plan is not found under another tenant's id, even though the order id matches -- proves the"
            + " tenant_id predicate in JdbcDeliveryPlanStore/JdbcAssignmentStore, not just order_id/plan_id")
    void aPlanUnderAnotherTenantIsNotFound() {
        // aPlanAtASiblingLocationIsNotFound (above) only proves the location boundary. Nothing
        // else in this suite ever calls delivery() with a tenantId that disagrees with the
        // plan's real tenant, so a future edit that dropped `tenant_id = :tenantId` from
        // JdbcDeliveryPlanStore.findByOrder/courierEtaByOrder or JdbcAssignmentStore.findShipment
        // -- leaving only order_id/plan_id, each already unique to this one tenant -- would still
        // pass every other test here.
        DeliveryPlan plan = openPlan();
        UUID otherTenant = UUID.randomUUID();

        Throwable refusal = catchThrowable(() -> controller.delivery(otherTenant, BRAND, branch, plan.orderId()));

        assertThat(refusal).isInstanceOf(ApiException.class);
    }

    // -------------------------------------------------------------- helpers

    private DeliveryPlan openPlan() {
        UUID orderId = seedDeliveryOrder();
        return planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
    }

    private void insertShipment(
            UUID shipmentId,
            DeliveryPlan plan,
            String sourceType,
            @Nullable UUID courierId,
            @Nullable UUID bindingId,
            @Nullable String providerType,
            Instant assignedAt,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt) {
        String status = deliveredAt != null ? "DELIVERED" : pickedUpAt != null ? "PICKED_UP" : "ASSIGNED";
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id,
                    status, source_type, courier_id, provider_binding_id, provider_type,
                    assigned_at, picked_up_at, delivered_at, version)
                VALUES (
                    :id, :tenantId, :brandId, :locationId, :orderId, :planId,
                    :status, :sourceType, :courierId, :bindingId, :providerType,
                    :assignedAt, :pickedUpAt, :deliveredAt, 1)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", plan.orderId())
                .param("planId", plan.id())
                .param("status", status)
                .param("sourceType", sourceType)
                .param("courierId", courierId)
                .param("bindingId", bindingId)
                .param("providerType", providerType)
                .param("assignedAt", assignedAt == null ? null : assignedAt.atOffset(ZoneOffset.UTC))
                .param("pickedUpAt", pickedUpAt == null ? null : pickedUpAt.atOffset(ZoneOffset.UTC))
                .param("deliveredAt", deliveredAt == null ? null : deliveredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID seedCourier() {
        UUID courierId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :tenantId, 'SCOOTER', 'Scooter', 'SCOOTER', 2, 60, 'ACTIVE')
                """).param("id", typeId).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, 'keycloak-K001', 'K-001', 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("tenantId", TENANT)
                .param("typeId", typeId)
                .update();
        return courierId;
    }

    private UUID seedBinding() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('yandex-test', 'DELIVERY', 'yandex-delivery', 'https://yandex.test', false, 'yandex.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', 'yandex-delivery', 'yandex-test', 'yandex-delivery',
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("secret", "horecaos:test:provider_delivery:tenant:yandex-delivery")
                .update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id,
                    location_id, status, priority)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .update();
        return bindingId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "order-delivery-tenant").update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        siblingBranch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES
                    (:branch, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                     'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN'),
                    (:sibling, :tenantId, :brandId, 'NORTH', 'north', 'North', 'Asia/Tashkent',
                     'ACTIVE', 0, 41.35, 69.29, 'MERCHANT_PIN')
                """)
                .param("branch", branch)
                .param("sibling", siblingBranch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "order-delivery-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "OD-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return orderId;
    }

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

    /** Answers for whichever order id it is asked about — this suite only ever plans one at a time. */
    private final class SingleOrder implements DeliveryOrderPort {

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            return Optional.of(new DeliveryOrder(
                    orderId,
                    "OD-" + sequence,
                    Duration.ofMinutes(15),
                    12_000L,
                    null,
                    "UZS",
                    true,
                    50_000L,
                    new Waypoint(41.325, 69.281, "Home", "Customer", "+998900000002", null, "2", "5", "17")));
        }
    }
}
