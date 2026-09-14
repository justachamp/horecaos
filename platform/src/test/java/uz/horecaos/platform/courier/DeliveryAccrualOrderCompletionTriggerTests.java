package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.AdjustmentRuleEvaluator;
import uz.horecaos.platform.courier.application.CourierAccrualService;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierLedgerService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryAccrualOrderCompletionTrigger;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.fulfillment.infrastructure.sourcing.JdbcDeliveryCompletionAdapter;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * T11's root cause, before its reports (ADR 0042, ADR 0125): {@code
 * CourierAccrualService.recordDelivery} had no production caller. Against a
 * real PostgreSQL, for the reason {@code OrderCompletionAccrualTriggerTests}
 * already gives for loyalty's own trigger — whether a replay accrues twice is
 * a property of a real unique constraint, and whether the shipment actually
 * closes is a property of a real compare-and-set, neither of which a mock
 * stands in for.
 *
 * <p>Drives {@link DeliveryAccrualOrderCompletionTrigger#onOrderingEvent}
 * directly with a constructed {@link OrderCompleted}, the same shape {@code
 * OrderCompletionAccrualTriggerTests} uses for loyalty — {@code
 * OrderStateService}'s own suite already proves the event is published
 * correctly; this proves what happens once it is.
 */
class DeliveryAccrualOrderCompletionTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String UZS = "UZS";
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCourierStore courierStore;
    private JdbcCourierLedgerStore ledgerStore;
    private DeliveryAccrualOrderCompletionTrigger trigger;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID courierId;
    private int chainSequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for courier tests");
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
                TRUNCATE TABLE fulfillment.courier_ledger_entries,
                    fulfillment.courier_assignment_earnings,
                    fulfillment.courier_settlement_periods,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_rate_components,
                    fulfillment.courier_rate_cards,
                    fulfillment.courier_types,
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var protection = TestProtection.envelope();
        AuditRecorder audit = fact -> {};
        LegalEntityResolver legalEntities = (tenantId, locationId, businessDate) -> Optional.empty();
        var policyResolver = new CourierPolicyResolver(new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        });

        courierStore = new JdbcCourierStore(jdbc);
        var shiftStore = new JdbcCourierShiftStore(jdbc);
        ledgerStore = new JdbcCourierLedgerStore(jdbc);
        var rateCardStore = new JdbcCourierRateCardStore(jdbc);
        var costStore = new JdbcDeliveryCostStore(jdbc);

        var ledger = new CourierLedgerService(ledgerStore, courierStore, policyResolver, legalEntities, clock);
        var engagements = new CourierEngagementService(
                courierStore, protection, audit, policyResolver, (tenantId, assetIds) -> false, clock);
        var approvals = new ApprovalService() {
            @Override
            public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
                return new ApprovalOutcome.NotRequired();
            }

            @Override
            public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public int expireOverdue() {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        };
        var adjustments = new CourierAdjustmentService(courierStore, ledger, approvals, audit, policyResolver, clock);
        var adjustmentRules = new AdjustmentRuleEvaluator(courierStore, ledgerStore, adjustments);
        var shifts = new CourierShiftService(
                shiftStore,
                courierStore,
                ledgerStore,
                rateCardStore,
                ledger,
                policyResolver,
                protection,
                audit,
                adjustmentRules,
                clock);
        var accruals = new CourierAccrualService(
                ledgerStore,
                rateCardStore,
                shiftStore,
                courierStore,
                costStore,
                ledger,
                policyResolver,
                legalEntities,
                protection);
        var rateCards = new CourierRateCardService(rateCardStore, audit, clock);
        var deliveryCompletion = new JdbcDeliveryCompletionAdapter(jdbc);

        trigger = new DeliveryAccrualOrderCompletionTrigger(deliveryCompletion, accruals, shifts);

        seedTenancy();
        seedCourier(engagements);
        seedRateCard(rateCards);
    }

    @Test
    @DisplayName("an order completed with an internal delivery accrues the courier and collects cash")
    void anInternalDeliveryAccruesOnOrderCompletion() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        Map<String, Object> shipment = shipmentRow(fixture.shipmentId());
        assertThat(shipment).containsEntry("status", "DELIVERED");
        assertThat(shipment.get("delivered_at")).isNotNull();

        Optional<JdbcCourierLedgerStore.EarningRow> earning =
                ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId());
        assertThat(earning).isPresent();
        assertThat(earning.orElseThrow().courierId()).isEqualTo(courierId);
        assertThat(earning.orElseThrow().totalMinor()).isGreaterThan(0);

        List<LedgerEntryRow> cashEntries = entriesOfType(LedgerEntryType.CASH_COLLECTED);
        assertThat(cashEntries).hasSize(1);
        // A CASH_COLLECTED entry is a liability the ledger owes back, hence negative.
        assertThat(cashEntries.getFirst().amountMinor()).isEqualTo(-45_000L);
    }

    @Test
    @DisplayName("a prepaid order accrues the courier but collects no cash")
    void aPrepaidOrderCollectsNoCash() {
        Fixture fixture = seedAssignedShipment("CAPTURED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        assertThat(ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()))
                .isPresent();
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED)).isEmpty();
    }

    @Test
    @DisplayName("a replayed OrderCompleted accrues exactly once and closes the shipment exactly once")
    void aReplayedEventDoesNotDoubleAccrue() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        OrderCompleted event = orderCompleted(fixture.orderId(), 20_000);

        trigger.onOrderingEvent(event);
        trigger.onOrderingEvent(event);

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                        .filter(entry -> entry.entryType() == LedgerEntryType.DELIVERY_EARNING)
                        .count())
                .isEqualTo(1);
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED)).hasSize(1);
    }

    @Test
    @DisplayName("an order with no shipment (pickup) is a safe no-op")
    void aPickupOrderIsANoOp() {
        UUID orderId = seedOrder(++chainSequence, "NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(orderId, 30_000));

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 500)).isEmpty();
    }

    @Test
    @DisplayName("a missing rate card never blocks the shipment from closing")
    void aMissingRateCardStillClosesTheShipment() {
        // No rate card for this courier type at this branch: archive the one
        // seedRateCard activated, matching "a tenant still mid-onboarding".
        jdbc.sql("UPDATE fulfillment.courier_rate_cards SET status = 'ARCHIVED' WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        Map<String, Object> shipment = shipmentRow(fixture.shipmentId());
        assertThat(shipment).containsEntry("status", "DELIVERED");
        assertThat(ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()))
                .isEmpty();
    }

    // --------------------------------------------------------------- fixtures

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'accrual-trigger-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
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

    private void seedCourier(CourierEngagementService engagements) {
        UUID courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId, TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, 0, "SHIFT", "ACTIVE", 1));

        CourierEngagementService.Registration registration =
                engagements.register(new CourierEngagementService.NewCourier(
                        TENANT,
                        courierTypeId,
                        "keycloak-courier",
                        "K-001",
                        "Alisher Karimov",
                        LocalDate.ofInstant(NOW, ZoneOffset.UTC),
                        manager(),
                        "onboarding a rider",
                        "corr"));
        courierId = registration.courierId();

        engagements.verify(new CourierEngagementService.VerifyRegistration(
                TENANT,
                registration.engagementId(),
                "312345678901",
                LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted the registration certificate",
                "corr"));
    }

    private void seedRateCard(CourierRateCardService rateCards) {
        UUID rateCardId = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT,
                BRAND,
                null,
                null,
                "STANDARD",
                1,
                UZS,
                List.of(
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER, 0, 3_000, null, null, null),
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 2_000, 0, null, null))));
        rateCards.activate(TENANT, rateCardId, manager(), "activating the standard card");
    }

    /** An INTERNAL shipment ASSIGNED (not yet DELIVERED) to the fixture courier, on a real order. */
    private Fixture seedAssignedShipment(String paymentStatusProjection) {
        int sequence = ++chainSequence;
        UUID orderId = seedOrder(sequence, paymentStatusProjection);
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start,
                    pickup_window_end, promised_delivery_start, promised_delivery_end,
                    source_at, latest_assignment_at, branch_zone, distance_meters, distance_source)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'ASSIGNED', 'FLEET_FIRST',
                       'STANDARD', 12000, 'UZS', anchor, 900,
                       anchor + interval '15 minutes', anchor + interval '15 minutes',
                       anchor + interval '25 minutes', anchor + interval '30 minutes',
                       anchor + interval '45 minutes', anchor, anchor + interval '25 minutes',
                       'Asia/Tashkent', 4200, 'RADIUS'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", planId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("anchor", anchor)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, :planId, 'ASSIGNED',
                       'INTERNAL', :courierId, anchor + interval '5 minutes'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
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

        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (
                    id, tenant_id, delivery_plan_id, shipment_id, sequence_number, source_type,
                    courier_id, status, idempotency_key, decision_reason, shift_enforcement_mode,
                    requested_at, accepted_at)
                SELECT :id, :tenantId, :planId, :shipmentId, 1, 'INTERNAL', :courierId, 'ACCEPTED',
                       :idempotencyKey, 'FLEET_AVAILABLE', 'ADVISORY',
                       anchor + interval '1 minute', anchor + interval '5 minutes'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", attemptId)
                .param("tenantId", TENANT)
                .param("planId", planId)
                .param("shipmentId", shipmentId)
                .param("courierId", courierId)
                .param("idempotencyKey", "trigger-offer-" + sequence)
                .param("anchor", anchor)
                .update();

        return new Fixture(orderId, shipmentId, attemptId);
    }

    private UUID seedOrder(int sequence, String paymentStatusProjection) {
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String reference = "trigger-order-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        45000, 0, 45000, now() + interval '1 hour')
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
                    approval_channel_snapshot, status, payment_status_projection, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'COMPLETED', :paymentStatus,
                        'UZS', 45000, 0, 45000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "T-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("paymentStatus", paymentStatusProjection)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return orderId;
    }

    private OrderCompleted orderCompleted(UUID orderId, long totalMinor) {
        return new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, branch, NOW, UZS, totalMinor, 1);
    }

    private Map<String, Object> shipmentRow(UUID shipmentId) {
        return jdbc.sql("SELECT status, delivered_at FROM fulfillment.shipments WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", shipmentId)
                .query()
                .singleRow();
    }

    private List<LedgerEntryRow> entriesOfType(LedgerEntryType type) {
        return ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                .filter(entry -> entry.entryType() == type)
                .toList();
    }

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    private record Fixture(UUID orderId, UUID shipmentId, UUID attemptId) {}
}
