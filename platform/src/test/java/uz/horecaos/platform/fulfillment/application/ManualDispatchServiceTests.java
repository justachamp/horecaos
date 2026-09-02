package uz.horecaos.platform.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
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
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The dispatch board's manual assign/unassign (operations §3.1), against a real
 * PostgreSQL — the single-winner and blocked-once-picked-up properties are the
 * database's own partial unique indexes and {@code WHERE status IN (...)}
 * clauses, not something a mock can stand in for. Setup mirrors {@code
 * DeliverySourcingTests}: a real {@link DeliveryPlanningService} opens the plan
 * this suite then manually dispatches, so the plan under test is exactly what
 * ADR 0014 would have produced from a real confirmation.
 */
class ManualDispatchServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final Instant CONFIRMED = Instant.parse("2026-08-25T12:00:00Z");
    private static final UUID COURIER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ManualDispatchService dispatch;
    private JdbcAssignmentStore assignments;
    private DeliveryPlanningService planning;
    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for manual dispatch tests");
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
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    fulfillment.delivery_sourcing_jobs,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CONFIRMED, ZoneOffset.UTC);
        JdbcDeliveryPlanStore planStore = new JdbcDeliveryPlanStore(jdbc);
        assignments = new JdbcAssignmentStore(jdbc);
        dispatch = new ManualDispatchService(planStore, assignments, clock);

        seedTenancy();
        seedCourier(COURIER, "K-001");
        planning = new DeliveryPlanningService(
                new SingleOrder(),
                planStore,
                new JdbcSourcingJobStore(jdbc),
                new JdbcDispatchBranchStore(jdbc),
                unconfigured(),
                clock);
    }

    @Test
    @DisplayName("assigning a courier creates the shipment and moves the plan to ASSIGNED")
    void assignCreatesTheShipmentAndMovesThePlan() {
        DeliveryPlan plan = openPlan();

        ManualDispatchService.DispatchOutcome outcome =
                dispatch.assign(TENANT, plan.id(), COURIER, plan.version(), "OPERATIONS_MANUAL_ASSIGN");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.planStatus()).isEqualTo(PlanStatus.ASSIGNED);
        assertThat(outcome.shipmentId()).isNotNull();

        var shipment = assignments.findShipment(TENANT, plan.id()).orElseThrow();
        assertThat(shipment.courierId()).isEqualTo(COURIER);
        assertThat(shipment.status().name()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("a second assign on an already-carried plan is a conflict, not a second courier")
    void aSecondAssignDoesNotProduceASecondShipment() {
        DeliveryPlan plan = openPlan();
        dispatch.assign(TENANT, plan.id(), COURIER, plan.version(), "OPERATIONS_MANUAL_ASSIGN");

        UUID otherCourier = UUID.fromString("44444444-4444-4444-4444-444444444444");
        seedCourier(otherCourier, "K-002");

        ManualDispatchService.DispatchOutcome second =
                dispatch.assign(TENANT, plan.id(), otherCourier, plan.version() + 1, "OPERATIONS_MANUAL_ASSIGN");

        assertThat(second.applied()).isFalse();
        assertThat(second.reason()).isEqualTo("ALREADY_ASSIGNED");
        assertThat(countShipmentsFor(plan.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("assigning against a stale plan version is refused before anything is written")
    void assignRefusesAStaleVersion() {
        DeliveryPlan plan = openPlan();

        ManualDispatchService.DispatchOutcome outcome =
                dispatch.assign(TENANT, plan.id(), COURIER, plan.version() + 1, "OPERATIONS_MANUAL_ASSIGN");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).isEqualTo("STALE_VERSION");
        assertThat(countShipmentsFor(plan.id())).isZero();
    }

    @Test
    @DisplayName("unassigning returns the plan to the sourcing pool and cancels the shipment")
    void unassignReturnsThePlanToSourcing() {
        DeliveryPlan plan = openPlan();
        var assigned = dispatch.assign(TENANT, plan.id(), COURIER, plan.version(), "OPERATIONS_MANUAL_ASSIGN");
        var shipment = assignments.findShipment(TENANT, plan.id()).orElseThrow();

        ManualDispatchService.DispatchOutcome outcome =
                dispatch.unassign(TENANT, plan.id(), shipment.version(), "OPERATIONS_UNASSIGN");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.planStatus()).isEqualTo(PlanStatus.WAITING_TO_SOURCE);
        assertThat(assignments.findShipment(TENANT, plan.id())).isEmpty();
        assertThat(assigned.planStatus()).isEqualTo(PlanStatus.ASSIGNED);
    }

    @Test
    @DisplayName("a courier already carrying the order cannot be unassigned out from under them")
    void unassignIsRefusedOncePickedUp() {
        DeliveryPlan plan = openPlan();
        dispatch.assign(TENANT, plan.id(), COURIER, plan.version(), "OPERATIONS_MANUAL_ASSIGN");
        var shipment = assignments.findShipment(TENANT, plan.id()).orElseThrow();
        jdbc.sql("UPDATE fulfillment.shipments SET status = 'PICKED_UP', picked_up_at = now() WHERE id = :id")
                .param("id", shipment.id())
                .update();

        ManualDispatchService.DispatchOutcome outcome =
                dispatch.unassign(TENANT, plan.id(), shipment.version(), "OPERATIONS_UNASSIGN");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).isEqualTo("CANNOT_UNASSIGN");
        assertThat(assignments.findShipment(TENANT, plan.id())).isPresent();
    }

    // -------------------------------------------------------------- helpers

    private DeliveryPlan openPlan() {
        UUID orderId = seedDeliveryOrder();
        return planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
    }

    private long countShipmentsFor(UUID planId) {
        return jdbc.sql(
                        "SELECT count(*) FROM fulfillment.shipments WHERE delivery_plan_id = :planId AND status <> 'CANCELLED'")
                .param("planId", planId)
                .query(Long.class)
                .single();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "manual-dispatch-tenant")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", branch)
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

    private void seedCourier(UUID courierId, String reference) {
        UUID typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :tenantId, :code, 'Scooter', 'SCOOTER', 2, 60, 'ACTIVE')
                """)
                .param("id", typeId)
                .param("tenantId", TENANT)
                .param("code", "SCOOTER-" + reference)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("tenantId", TENANT)
                .param("typeId", typeId)
                .param("subject", "keycloak-" + reference)
                .param("reference", reference)
                .update();
    }

    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "manual-dispatch-" + sequence;

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
                .param("number", "MD-" + sequence)
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
                    "MD-" + sequence,
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
