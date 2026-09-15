package uz.horecaos.platform.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancelCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancellationReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancellationStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort.Result;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryExceptionReason;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryQuoteStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJournal;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Cascading cancel at the provider (ADR 0014, gap map row 1.2g), against a real
 * PostgreSQL — {@code cancelActiveShipment}'s {@code WHERE status IN (...)}
 * and {@code ux_exception_one_open} are the database's own, not something a
 * mock stands in for. Setup mirrors {@code ManualDispatchServiceTests}: a real
 * {@link DeliveryPlanningService} opens the plan this suite then cancels.
 */
class ShipmentCancellationServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant CONFIRMED = Instant.parse("2026-09-15T12:00:00Z");
    private static final ActorRef OPERATOR = ActorRef.user("operator-1", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcDeliveryPlanStore planStore;
    private JdbcAssignmentStore assignments;
    private JdbcDeliveryExceptionStore exceptionStore;
    private RecordingAudit audit;
    private RecordingRealtimeSignals realtime;
    private RecordingBookingPort bookings;
    private ShipmentCancellationService service;
    private DeliveryPlanningService planning;
    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for shipment cancel tests");
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
                    fulfillment.delivery_exceptions,
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
        exceptionStore = new JdbcDeliveryExceptionStore(jdbc);
        audit = new RecordingAudit();
        realtime = new RecordingRealtimeSignals();
        bookings = new RecordingBookingPort();

        SourcingJournal journal = new JdbcSourcingJournal(
                assignments,
                new JdbcDeliveryQuoteStore(jdbc, JsonMapper.builder().build()),
                exceptionStore,
                new JdbcDeliveryCostSubsidyStore(jdbc),
                planStore);

        service = new ShipmentCancellationService(planStore, assignments, bookings, journal, audit, realtime, clock);

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
    @DisplayName("cascading cancel tells the provider even after the courier has already picked up, and "
            + "marks both the shipment and the plan cancelled on a free cancellation")
    void cascadeCancelsAnAlreadyPickedUpPartnerShipment() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        UUID shipmentId = insertPartnerShipment(plan, bindingId, "ext-noor-1", "PICKED_UP");
        bookings.cancelStatus = CancellationStatus.CANCELLED;

        var outcome = service.cancelForOrder(TENANT, BRAND, branch, plan.orderId(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.PROVIDER_CANCELLED);
        assertThat(outcome.providerType()).isEqualTo("noor-delivery");
        assertThat(bookings.cancelled).hasSize(1);
        assertThat(bookings.cancelled.getFirst().externalReference()).isEqualTo("ext-noor-1");
        assertThat(bookings.cancelled.getFirst().bindingId()).isEqualTo(bindingId);

        assertThat(shipmentStatus(shipmentId)).isEqualTo("CANCELLED");
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(audit.facts)
                .extracting(AuditFact::actionCode)
                .containsExactly("fulfillment.shipment.cascade-cancel");
        assertThat(realtime.signals).hasSize(1);
    }

    @Test
    @DisplayName("a chargeable provider cancellation is still recorded as cancelled -- the cost is a fact "
            + "for the audit trail, not a reason to leave the shipment live")
    void aChargeableCancellationStillCancelsTheShipment() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        insertPartnerShipment(plan, bindingId, "ext-yandex-1", "ASSIGNED");
        bookings.cancelStatus = CancellationStatus.CANCELLED_WITH_COST;

        var outcome = service.cancelForOrder(TENANT, BRAND, branch, plan.orderId(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.PROVIDER_CANCELLED_CHARGEABLE);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status()).isEqualTo(PlanStatus.CANCELLED);
    }

    @Test
    @DisplayName("an UNCERTAIN provider response never marks the shipment cancelled -- it opens "
            + "fulfillment.delivery_exceptions and leaves the plan MANUAL_ACTION_REQUIRED for a human")
    void anUncertainCancelOpensADeliveryException() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        UUID shipmentId = insertPartnerShipment(plan, bindingId, "ext-uncertain-1", "ASSIGNED");
        bookings.cancelStatus = CancellationStatus.UNCERTAIN;

        var outcome = service.cancelForOrder(TENANT, BRAND, branch, plan.orderId(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.PROVIDER_UNCERTAIN);
        // The shipment is left exactly as it was: the provider might still be
        // carrying this order, and marking it CANCELLED here would be exactly
        // the silent gap this cascade exists to close.
        assertThat(shipmentStatus(shipmentId)).isEqualTo("ASSIGNED");
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status())
                .isEqualTo(PlanStatus.MANUAL_ACTION_REQUIRED);

        List<JdbcDeliveryExceptionStore.OpenException> open = exceptionStore.open(TENANT, plan.id());
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().reasonCode()).isEqualTo(DeliveryExceptionReason.PROVIDER_CANCEL_UNCERTAIN);
    }

    @Test
    @DisplayName("a provider refusal is the same honest MANUAL_ACTION_REQUIRED outcome as UNCERTAIN, under "
            + "its own reason code")
    void aProviderRefusalAlsoNeedsAHuman() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        insertPartnerShipment(plan, bindingId, "ext-rejected-1", "ASSIGNED");
        bookings.cancelStatus = CancellationStatus.REJECTED;

        var outcome = service.cancelForOrder(TENANT, BRAND, branch, plan.orderId(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.PROVIDER_FAILED);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status())
                .isEqualTo(PlanStatus.MANUAL_ACTION_REQUIRED);
        assertThat(exceptionStore.open(TENANT, plan.id()))
                .extracting(JdbcDeliveryExceptionStore.OpenException::reasonCode)
                .containsExactly(DeliveryExceptionReason.PROVIDER_CANCEL_FAILED);
    }

    @Test
    @DisplayName("an in-house courier's shipment is cancelled locally, and the provider port is never called")
    void internalShipmentIsCancelledLocally() {
        DeliveryPlan plan = openPlan();
        UUID courierId = seedCourier();
        UUID shipmentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id,
                    status, source_type, courier_id, assigned_at, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId,
                        'ASSIGNED', 'INTERNAL', :courierId, :now, 1)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", plan.orderId())
                .param("planId", plan.id())
                .param("courierId", courierId)
                .param("now", CONFIRMED.atOffset(ZoneOffset.UTC))
                .update();

        var outcome = service.cancelForOrder(TENANT, BRAND, branch, plan.orderId(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.INTERNAL_CANCELLED);
        assertThat(bookings.cancelled)
                .as("an internal courier's provider is never called")
                .isEmpty();
        assertThat(shipmentStatus(shipmentId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an order with no delivery plan has nothing to cascade -- pickup and dine-in orders included")
    void noPlanIsNoOp() {
        var outcome = service.cancelForOrder(TENANT, BRAND, branch, UUID.randomUUID(), "CUSTOMER_REQUEST", OPERATOR);

        assertThat(outcome.result()).isEqualTo(Result.NOTHING_TO_CANCEL);
        assertThat(bookings.cancelled).isEmpty();
        assertThat(audit.facts).isEmpty();
    }

    @Test
    @DisplayName("the dedicated shipment-cancel endpoint refuses a stale version before calling the provider")
    void shipmentCancelRefusesAStaleVersion() {
        DeliveryPlan plan = openPlan();
        UUID bindingId = seedBinding();
        UUID shipmentId = insertPartnerShipment(plan, bindingId, "ext-stale-1", "ASSIGNED");

        var outcome = service.cancelShipment(TENANT, BRAND, branch, shipmentId, 99, "OPERATOR_MANUAL", OPERATOR);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.conflictReason()).isEqualTo("STALE_VERSION");
        assertThat(bookings.cancelled).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private DeliveryPlan openPlan() {
        UUID orderId = seedDeliveryOrder();
        return planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
    }

    private UUID insertPartnerShipment(DeliveryPlan plan, UUID bindingId, String externalReference, String status) {
        UUID shipmentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id,
                    status, source_type, provider_binding_id, provider_type,
                    external_shipment_id, assigned_at, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId,
                        :status, 'PARTNER', :bindingId, 'noor-delivery', :externalRef, :now, 1)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", plan.orderId())
                .param("planId", plan.id())
                .param("status", status)
                .param("bindingId", bindingId)
                .param("externalRef", externalReference)
                .param("now", CONFIRMED.atOffset(ZoneOffset.UTC))
                .update();
        // A real win() would have moved the plan to ASSIGNED the instant this
        // shipment was created; the fixture does the same so the plan this
        // cascade reads is the one a real booking would have left behind.
        planStore.transition(TENANT, plan.id(), PlanStatus.PLANNED, PlanStatus.ASSIGNED, CONFIRMED);
        return shipmentId;
    }

    private UUID seedBinding() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('noor-test', 'DELIVERY', 'noor-delivery', 'https://noor.test', false, 'noor.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', 'noor-delivery', 'noor-test', 'noor-delivery',
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("secret", "horecaos:test:provider_delivery:tenant:noor-delivery")
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

    private String shipmentStatus(UUID shipmentId) {
        return jdbc.sql("SELECT status FROM fulfillment.shipments WHERE id = :id")
                .param("id", shipmentId)
                .query(String.class)
                .single();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "shipment-cancel-tenant")
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

    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "shipment-cancel-" + sequence;

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
                .param("number", "SC-" + sequence)
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

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }

    private static final class RecordingRealtimeSignals implements RealtimeSignalPublisher {

        private final List<RealtimeSignal> signals = new ArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            signals.add(signal);
        }
    }

    /** Answers whatever {@link ShipmentBookingPort#cancel} the test configured. Never asked to book. */
    private static final class RecordingBookingPort implements ShipmentBookingPort {

        private final List<CancelCommand> cancelled = new ArrayList<>();
        private CancellationStatus cancelStatus = CancellationStatus.CANCELLED;

        @Override
        public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
            return List.of();
        }

        @Override
        public BookingReceipt book(BookingCommand command) {
            throw new UnsupportedOperationException("This suite never books; only cancels");
        }

        @Override
        public CancellationReceipt cancel(CancelCommand command) {
            cancelled.add(command);
            boolean succeeded = cancelStatus == CancellationStatus.CANCELLED
                    || cancelStatus == CancellationStatus.CANCELLED_WITH_COST;
            return CancellationReceipt.of(
                    cancelStatus,
                    command,
                    "noor-delivery",
                    succeeded ? null : "PARTNER_ERROR",
                    succeeded ? null : "the fixture partner refused");
        }
    }

    /** Answers for whichever order id it is asked about — this suite only ever plans one at a time. */
    private final class SingleOrder implements DeliveryOrderPort {

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            return Optional.of(new DeliveryOrder(
                    orderId,
                    "SC-" + sequence,
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
