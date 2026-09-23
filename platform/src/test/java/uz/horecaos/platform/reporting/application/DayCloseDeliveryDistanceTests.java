package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave 9 w4-reports-distance-crm (7.1, V0387): the close job now copies the
 * delivery leg's resolved distance (ADR 0037's {@code
 * fulfillment.delivery_plans.distance_meters}, snapshotted at pricing time)
 * onto {@code reporting.fact_order.delivery_distance_meters}.
 *
 * <p>Self-contained, deliberately not added to {@code
 * DayCloseAndMetricLayerTests} — the same isolation {@code
 * DayCloseDeliveryFactTests} and {@code DayCloseTenderFactTests} already
 * chose, for the same reason: that file is shared by every reporting wave in
 * flight.
 */
class DayCloseDeliveryDistanceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2026-09-01T10:40:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private DayCloseService close;
    private UUID branch;
    private UUID courierId;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for reporting tests");
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

        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line,
                    reporting.fact_order_tender, reporting.fact_refund, reporting.agg_branch_day,
                    reporting.agg_sla_bucket_day, reporting.fact_delivery,
                    reporting.business_day_policies, reporting.metric_definitions, reporting.fact_call_hour
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE fulfillment.assignment_attempts, fulfillment.shipments,
                    fulfillment.delivery_plans, fulfillment.couriers, fulfillment.courier_types,
                    ordering.orders, ordering.carts, pricing.quotes,
                    catalog.publications, catalog.catalogs, tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CLOSED_AT.plusSeconds(3600), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        BusinessDayService businessDays = new BusinessDayService(store);
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(new NoopProtection()), clock);

        seedTenancy();
        courierId = seedCourier();
        seedCatalog();
    }

    /**
     * The channel and published catalog every {@link #seedOrder} call reuses.
     * Seeded once per test, not once per order: {@code sales_channels.code}
     * and {@code catalogs.code} are unique per tenant, and a test that seeds
     * several orders (the averaging test below) would otherwise collide on
     * its second call.
     */
    private void seedCatalog() {
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
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    /**
     * A minimal courier, existing only so a fixture shipment can satisfy
     * {@code ck_shipment_internal_courier} — this suite does not exercise
     * courier behaviour and never reveals the placeholder name.
     */
    private UUID seedCourier() {
        UUID typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", typeId).param("tenantId", TENANT).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id, principal_subject,
                    display_reference, protected_full_name)
                VALUES (:id, :tenantId, :typeId, :principal, 'K-DIST', 'not-a-real-name')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("typeId", typeId)
                .param("principal", "keycloak-distance-fixture")
                .update();
        return id;
    }

    @Test
    @DisplayName("close copies the delivery plan's distance onto the order fact")
    void closeCopiesDeliveryDistanceOntoTheOrderFact() {
        UUID orderId = seedOrder("DELIVERY");
        UUID planId = UUID.randomUUID();
        seedDeliveryPlan(orderId, planId, 4_200, "ROUTING");
        seedShipment(orderId, planId);

        close.close(TENANT, DAY);

        Integer distance = jdbc.sql(
                        "SELECT delivery_distance_meters FROM reporting.fact_order WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(Integer.class)
                .single();

        assertThat(distance).isEqualTo(4_200);
    }

    @Test
    @DisplayName("the order fact's distance reconciles to the shipment's own plan, not a copy")
    void distanceReconcilesToTheShipmentsOwnPlan() {
        UUID orderId = seedOrder("DELIVERY");
        UUID planId = UUID.randomUUID();
        seedDeliveryPlan(orderId, planId, 6_150, "PROVIDER_QUOTE");
        UUID shipmentId = seedShipment(orderId, planId);

        close.close(TENANT, DAY);

        Integer planDistance = jdbc.sql(
                        "SELECT distance_meters FROM fulfillment.delivery_plans WHERE tenant_id = :t AND id = :p")
                .param("t", TENANT)
                .param("p", planId)
                .query(Integer.class)
                .single();
        UUID shipmentPlanId = jdbc.sql(
                        "SELECT delivery_plan_id FROM fulfillment.shipments WHERE tenant_id = :t AND id = :s")
                .param("t", TENANT)
                .param("s", shipmentId)
                .query(UUID.class)
                .single();
        Integer factDistance = jdbc.sql(
                        "SELECT delivery_distance_meters FROM reporting.fact_order WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(Integer.class)
                .single();

        assertThat(shipmentPlanId).isEqualTo(planId);
        assertThat(factDistance)
                .as("the fact's distance must reconcile to the shipment's own delivery plan")
                .isEqualTo(planDistance);
    }

    @Test
    @DisplayName("a pickup order has no delivery leg and reads null rather than a fabricated distance")
    void pickupOrderReadsNullDistance() {
        UUID orderId = seedOrder("PICKUP");

        close.close(TENANT, DAY);

        Integer distance = jdbc.sql(
                        "SELECT delivery_distance_meters FROM reporting.fact_order WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(Integer.class)
                .optional()
                .orElse(null);

        assertThat(distance).isNull();
    }

    @Test
    @DisplayName("a delivery order whose plan never resolved a distance reads null, not zero")
    void deliveryOrderWithNoResolvedDistanceReadsNull() {
        UUID orderId = seedOrder("DELIVERY");
        UUID planId = UUID.randomUUID();
        seedDeliveryPlan(orderId, planId, null, null);
        seedShipment(orderId, planId);

        close.close(TENANT, DAY);

        Integer distance = jdbc.sql(
                        "SELECT delivery_distance_meters FROM reporting.fact_order WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(Integer.class)
                .optional()
                .orElse(null);

        assertThat(distance).isNull();
    }

    @Test
    @DisplayName("averageDeliveryDistanceMeters means only delivery orders that resolved a distance")
    void averageDeliveryDistanceExcludesPickupAndUnresolvedOrders() {
        UUID delivery1 = seedOrder("DELIVERY");
        UUID plan1 = UUID.randomUUID();
        seedDeliveryPlan(delivery1, plan1, 4_000, "ROUTING");
        seedShipment(delivery1, plan1);

        UUID delivery2 = seedOrder("DELIVERY");
        UUID plan2 = UUID.randomUUID();
        seedDeliveryPlan(delivery2, plan2, 6_000, "ROUTING");
        seedShipment(delivery2, plan2);

        // Never counted: a pickup order (no plan at all) and a delivery order
        // whose plan resolved no distance. If either leaked in, the mean below
        // would not land on the exact midpoint of the two resolved deliveries.
        seedOrder("PICKUP");
        UUID unresolvedDelivery = seedOrder("DELIVERY");
        UUID unresolvedPlan = UUID.randomUUID();
        seedDeliveryPlan(unresolvedDelivery, unresolvedPlan, null, null);
        seedShipment(unresolvedDelivery, unresolvedPlan);

        close.close(TENANT, DAY);

        Integer average = store.averageDeliveryDistanceMeters(TENANT, DAY, DAY, List.of());

        assertThat(average).isEqualTo(5_000);
    }

    @Test
    @DisplayName("averageDeliveryDistanceMeters excludes a delivery order that is still open")
    void averageDeliveryDistanceExcludesOpenOrders() {
        UUID closedDelivery = seedOrder("DELIVERY");
        UUID closedPlan = UUID.randomUUID();
        seedDeliveryPlan(closedDelivery, closedPlan, 4_000, "ROUTING");
        seedShipment(closedDelivery, closedPlan);

        // Still in flight — e.g. PREPARING, not yet delivered, possibly headed
        // for cancellation — but its delivery plan already resolved a distance,
        // the way a plan resolves right after order confirmation, long before
        // the order itself closes. Never counted: the registry documents this
        // metric's population as delivery orders "closed in range", not every
        // delivery order that merely has a resolved distance.
        UUID openDelivery = seedOrder("DELIVERY", "PREPARING", null);
        UUID openPlan = UUID.randomUUID();
        seedDeliveryPlan(openDelivery, openPlan, 8_000, "ROUTING");
        seedShipment(openDelivery, openPlan);

        close.close(TENANT, DAY);

        Integer average = store.averageDeliveryDistanceMeters(TENANT, DAY, DAY, List.of());

        assertThat(average)
                .as("the open order's 8,000m plan must not be averaged in alongside the closed order's 4,000m")
                .isEqualTo(4_000);
    }

    // --------------------------------------------------------------- fixture

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'distance-fact-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void seedDeliveryPlan(
            UUID orderId, UUID planId, @Nullable Integer distanceMeters, @Nullable String distanceSource) {
        OffsetDateTime anchor = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start, pickup_window_end,
                    source_at, latest_assignment_at, branch_zone, distance_meters, distance_source)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'ASSIGNED', 'FLEET_FIRST',
                       'STANDARD', 12000, 'UZS', anchor, 900, anchor, anchor,
                       anchor + interval '10 minutes', anchor, anchor + interval '10 minutes',
                       'Asia/Tashkent', :distanceMeters, :distanceSource
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", planId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("anchor", anchor)
                .param("distanceMeters", distanceMeters)
                .param("distanceSource", distanceSource)
                .update();
    }

    private UUID seedShipment(UUID orderId, UUID planId) {
        UUID shipmentId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId, 'ASSIGNED',
                        'INTERNAL', :courierId, :anchor)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("courierId", courierId)
                .param("anchor", anchor)
                .update();
        return shipmentId;
    }

    private UUID seedOrder(String fulfilmentMode) {
        return seedOrder(fulfilmentMode, "COMPLETED", CLOSED_AT);
    }

    /**
     * Same fixture, with the order's own status and {@code closed_at}
     * exposed — so a test can seed a delivery order that is still open
     * (e.g. {@code PREPARING}, {@code closedAt} null) the way a branch's
     * close job actually meets one mid-shift: created and confirmed, its
     * delivery plan already resolved a distance, but not yet delivered and
     * not yet in {@link #seedOrder(String)}'s always-{@code COMPLETED}
     * shape.
     */
    private UUID seedOrder(String fulfilmentMode, String status, @Nullable Instant closedAt) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash', 45000, 0,
                        45000, now() + interval '1 hour')
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
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :mode, 'UZS', 'ACTIVE',
                        'distance-fixture', now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("mode", fulfilmentMode)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, created_at, confirmed_at,
                    closed_at)
                VALUES (:id, :orderNumber, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        'distance-fixture', :mode, 'AUTO_CONFIRM', 0, 'NONE', :status, 'UZS', 45000, 0,
                        45000, :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey, 1, :createdAt,
                        :createdAt, :closedAt)
                """)
                .param("id", orderId)
                .param("orderNumber", "F-" + orderId.toString().substring(0, 6))
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("mode", fulfilmentMode)
                .param("status", status)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .param("idempotencyKey", "distance-fixture-" + orderId)
                .param("createdAt", OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC))
                .param("closedAt", closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC))
                .update();
        return orderId;
    }

    /** A protection double that never runs: this fixture protects no PII fields. */
    private static final class NoopProtection implements FieldProtection {
        @Override
        public uz.horecaos.platform.iam.api.protection.ProtectedValue protect(
                UUID tenantId,
                uz.horecaos.platform.iam.api.protection.DataClass dataClass,
                RecordRef record,
                String plaintext) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public String reveal(
                UUID tenantId,
                uz.horecaos.platform.iam.api.protection.ProtectedValue value,
                RecordRef record,
                String purpose) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }
}
