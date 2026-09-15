package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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
import uz.horecaos.platform.courier.application.CourierAccrualService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierLedgerService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.DistanceSource;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.reporting.domain.SlaBucketSet;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * T11 / ADR 0125: {@code reporting.fact_delivery}'s close-time producer
 * inside {@link DayCloseService#close}, and the {@code COURIER} scope of
 * {@code agg_sla_bucket_day} it now also writes.
 *
 * <p>A self-contained fixture, deliberately not added to {@code
 * DayCloseAndMetricLayerTests} — the same isolation {@code
 * DayCloseTenderFactTests} already chose for P39, for the same reason: that
 * file is shared by every reporting wave in flight.
 *
 * <p>Drives a real {@code CourierAccrualService.recordDelivery} to produce
 * the {@code courier_assignment_earnings} row the close reads, rather than
 * inserting one by hand — a fixture that writes what production never would
 * proves nothing, and the accrual's own {@code business_date} and rate-card
 * arithmetic are exactly what a hand-built row would have to fake.
 */
class DayCloseDeliveryFactTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String UZS = "UZS";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-09-01T10:22:00Z"); // 22 minutes: UNDER_30

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private DayCloseService close;
    private UUID branch;
    private UUID courierId;

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
                TRUNCATE TABLE fulfillment.courier_assignment_earnings,
                    fulfillment.courier_settlement_periods, fulfillment.courier_engagements,
                    fulfillment.couriers, fulfillment.courier_rate_components,
                    fulfillment.courier_rate_cards, fulfillment.courier_types,
                    fulfillment.assignment_attempts, fulfillment.shipments, fulfillment.delivery_plans,
                    ordering.orders, ordering.carts, pricing.quotes,
                    catalog.publications, catalog.catalogs, tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(DELIVERED_AT.plus(Duration.ofDays(1)), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        BusinessDayService businessDays = new BusinessDayService(store);
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(new NoopProtection()), clock);

        seedTenancy();
        courierId = seedCourierDeliveryEarning();
    }

    @Test
    @DisplayName("close writes one reporting.fact_delivery row from the delivered earning")
    void closeProducesOneDeliveryFact() {
        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT courier_id, distance_meters, distance_source, on_time_outcome, transit_seconds
                  FROM reporting.fact_delivery
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("courier_id")).isEqualTo(courierId);
        assertThat(row.get("distance_meters")).isEqualTo(4200);
        assertThat(row.get("distance_source")).isEqualTo("ROUTING");
        assertThat(row.get("on_time_outcome")).isEqualTo("UNKNOWN"); // no promise recorded in this fixture
        assertThat(row.get("transit_seconds")).isEqualTo(1320); // 22 minutes
    }

    @Test
    @DisplayName("close writes the COURIER scope of agg_sla_bucket_day, bucketed on transit seconds")
    void closeProducesCourierSlaBucket() {
        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT scope_id, bucket_code, order_count, share_basis_points
                  FROM reporting.agg_sla_bucket_day
                 WHERE tenant_id = :t AND business_date = :d AND scope_kind = 'COURIER'
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("scope_id")).isEqualTo(courierId);
        assertThat(row.get("bucket_code"))
                .isEqualTo(SlaBucketSet.buckets().getFirst().code()); // UNDER_30
        assertThat(row.get("order_count")).isEqualTo(1);
        assertThat(row.get("share_basis_points")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("re-running close over an unchanged day reproduces the identical fact_delivery row")
    void closeIsIdempotentOverUnchangedFacts() {
        close.close(TENANT, DAY);
        List<Map<String, Object>> first = jdbc.sql(
                        "SELECT transit_seconds FROM reporting.fact_delivery WHERE tenant_id = :t AND business_date = :d")
                .param("t", TENANT)
                .param("d", DAY)
                .query()
                .listOfRows();

        close.close(TENANT, DAY);
        List<Map<String, Object>> second = jdbc.sql(
                        "SELECT transit_seconds FROM reporting.fact_delivery WHERE tenant_id = :t AND business_date = :d")
                .param("t", TENANT)
                .param("d", DAY)
                .query()
                .listOfRows();

        assertThat(second).isEqualTo(first);
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("close never mixes one tenant's delivery fact into another's")
    void closeNeverCrossesTenants() {
        UUID otherTenant = seedOtherTenantDeliveryFact();

        close.close(TENANT, DAY);
        close.close(otherTenant, DAY);

        List<Map<String, Object>> tenantRows = jdbc.sql(
                        "SELECT courier_id FROM reporting.fact_delivery WHERE tenant_id = :t AND business_date = :d")
                .param("t", TENANT)
                .param("d", DAY)
                .query()
                .listOfRows();
        List<Map<String, Object>> otherRows = jdbc.sql(
                        "SELECT courier_id FROM reporting.fact_delivery WHERE tenant_id = :t AND business_date = :d")
                .param("t", otherTenant)
                .param("d", DAY)
                .query()
                .listOfRows();

        assertThat(tenantRows).hasSize(1);
        assertThat(tenantRows.getFirst().get("courier_id")).isEqualTo(courierId);
        assertThat(otherRows).hasSize(1);
        assertThat(otherRows.getFirst().get("courier_id")).isNotEqualTo(courierId);
    }

    // --------------------------------------------------------------- fixture

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'delivery-fact-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

    /**
     * A second, wholly independent tenant with its own brand, location and
     * delivered earning — 2026-09-14 review: none of this suite's three
     * tests could previously have failed if {@code tenant_id} were dropped
     * from {@link JdbcReportingStore#readSourceDeliveries} or {@link
     * JdbcReportingStore#insertDeliveryFact}, since only {@link #TENANT}'s
     * rows ever existed in the database during a run.
     *
     * @return the other tenant's id
     */
    private UUID seedOtherTenantDeliveryFact() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal 2', 'Display 2', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "delivery-fact-other-tenant-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand 2', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre 2', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        seedCourierDeliveryEarning(tenantId, brandId, locationId, "K-OTHER", "312345678902");
        return tenantId;
    }

    /** Courier, rate card, a delivered shipment chain, and one real accrual — see the class doc for why. */
    private UUID seedCourierDeliveryEarning() {
        return seedCourierDeliveryEarning(TENANT, BRAND, branch, "K-001", "312345678901");
    }

    /** Isolation-test overload — see {@link #seedOtherTenantDeliveryFact()}. */
    private UUID seedCourierDeliveryEarning(
            UUID fixtureTenantId, UUID fixtureBrandId, UUID fixtureLocationId, String courierCode, String phone) {
        var protection = TestProtection.envelope();
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
        LegalEntityResolver legalEntities = (tenantId, locationId, businessDate) -> Optional.empty();
        // The rate card must be effective at or before ACCEPTED_AT — that is the
        // instant recordDelivery resolves it at, not deliveredAt.
        Clock accrualClock = Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);

        var courierStore = new JdbcCourierStore(jdbc);
        var shiftStore = new JdbcCourierShiftStore(jdbc);
        var ledgerStore = new JdbcCourierLedgerStore(jdbc);
        var rateCardStore = new JdbcCourierRateCardStore(jdbc);
        var costStore = new JdbcDeliveryCostStore(jdbc);
        var ledger = new CourierLedgerService(ledgerStore, courierStore, policyResolver, legalEntities, accrualClock);
        var engagements = new CourierEngagementService(
                courierStore, protection, ignored -> {}, policyResolver, (t, a) -> false, accrualClock);
        var rateCards = new CourierRateCardService(rateCardStore, ignored -> {}, accrualClock);
        var accruals = new CourierAccrualService(
                ledgerStore,
                rateCardStore,
                shiftStore,
                courierStore,
                costStore,
                ledger,
                policyResolver,
                legalEntities,
                protection,
                new CourierBusinessDayWindowsAdapter(new BusinessDayService(store)));

        UUID courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId,
                fixtureTenantId,
                "SCOOTER",
                "Scooter",
                "SCOOTER",
                0,
                15_000,
                2,
                60,
                0,
                "SHIFT",
                "ACTIVE",
                1));
        var registration = engagements.register(new CourierEngagementService.NewCourier(
                fixtureTenantId,
                courierTypeId,
                "keycloak-" + courierCode,
                courierCode,
                "Alisher Karimov",
                DAY,
                actor(),
                "onboarding",
                "corr"));
        UUID courierId = registration.courierId();
        engagements.verify(new CourierEngagementService.VerifyRegistration(
                fixtureTenantId,
                registration.engagementId(),
                phone,
                DAY.plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                actor(),
                "sighted",
                "corr"));

        UUID rateCardId = rateCards.author(new CourierRateCardService.NewRateCard(
                fixtureTenantId,
                fixtureBrandId,
                null,
                null,
                "STANDARD",
                1,
                UZS,
                List.of(new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 2_000, 0, null, null))));
        rateCards.activate(fixtureTenantId, rateCardId, actor(), "activating");

        UUID orderId = seedOrder(fixtureTenantId, fixtureBrandId, fixtureLocationId);
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(DELIVERED_AT, ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start, pickup_window_end,
                    source_at, latest_assignment_at, branch_zone)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'ASSIGNED', 'FLEET_FIRST',
                       'STANDARD', 12000, 'UZS', anchor, 900, anchor, anchor,
                       anchor + interval '10 minutes', anchor, anchor + interval '10 minutes',
                       'Asia/Tashkent'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", planId)
                .param("tenantId", fixtureTenantId)
                .param("brandId", fixtureBrandId)
                .param("locationId", fixtureLocationId)
                .param("orderId", orderId)
                .param("anchor", anchor)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId, 'ASSIGNED',
                        'INTERNAL', :courierId, :anchor)
                """)
                .param("id", shipmentId)
                .param("tenantId", fixtureTenantId)
                .param("brandId", fixtureBrandId)
                .param("locationId", fixtureLocationId)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("courierId", courierId)
                .param("anchor", anchor)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (
                    id, tenant_id, delivery_plan_id, shipment_id, sequence_number, source_type,
                    courier_id, status, idempotency_key, decision_reason, requested_at, accepted_at)
                VALUES (:id, :tenantId, :planId, :shipmentId, 1, 'INTERNAL', :courierId, 'ACCEPTED',
                        'day-close-fixture', 'FLEET_AVAILABLE', :anchor, :acceptedAt)
                """)
                .param("id", attemptId)
                .param("tenantId", fixtureTenantId)
                .param("planId", planId)
                .param("shipmentId", shipmentId)
                .param("courierId", courierId)
                .param("anchor", anchor)
                .param("acceptedAt", OffsetDateTime.ofInstant(ACCEPTED_AT, ZoneOffset.UTC))
                .update();

        accruals.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                fixtureTenantId,
                fixtureBrandId,
                fixtureLocationId,
                courierId,
                null,
                shipmentId,
                attemptId,
                4200,
                DistanceSource.ROUTING,
                ACCEPTED_AT,
                DELIVERED_AT,
                null, // no promise -> ON_TIME_OUTCOME = UNKNOWN
                300,
                1,
                null,
                null,
                0,
                false,
                null,
                null));

        return courierId;
    }

    private UUID seedOrder() {
        return seedOrder(TENANT, BRAND, branch);
    }

    /** Isolation-test overload — see {@link #seedOtherTenantDeliveryFact()}. */
    private UUID seedOrder(UUID tenantId, UUID brandId, UUID locationId) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID catalogId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash', 45000, 0,
                        45000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("publicationId", publicationId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS', 'ACTIVE',
                        'fact-fixture', now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, 'F-1', :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        'fact-fixture', 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'COMPLETED', 'UZS', 45000, 0,
                        45000, :quoteId, 'hash', :publicationId, :cartId, 'fact-fixture', 1, now())
                """)
                .param("id", orderId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();
        return orderId;
    }

    private static uz.horecaos.platform.audit.api.ActorRef actor() {
        return uz.horecaos.platform.audit.api.ActorRef.user("keycloak-manager", "Manager");
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
