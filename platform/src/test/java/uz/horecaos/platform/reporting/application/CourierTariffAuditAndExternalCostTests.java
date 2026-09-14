package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.domain.MatchStatus;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * T11 7.4b/7.4c (ADR 0125): the delivery-sum-by-tariff audit and the
 * per-order external-delivery-cost report, including {@code UNBILLED}'s
 * first-ever producer — {@code MatchStatus.UNBILLED} was a dead enum
 * constant before this: nothing wrote it and nothing read it.
 *
 * <p>Rows inserted directly, the same choice {@code OperatorReportingTests}
 * makes and states why: {@code delivery_fee_resolutions} and {@code
 * partner_delivery_invoice_lines} are operational tables this wave reads
 * live, not a derived fact table with its own projector to exercise —
 * {@link CourierTariffAuditAndExternalCostTests} is the query layer's own
 * test, not a second copy of {@code DayCloseDeliveryFactTests}.
 */
class CourierTariffAuditAndExternalCostTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID TARIFF = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final Instant RESOLVED_AT = Instant.parse("2026-09-01T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ReportQueryService queries;
    private JdbcDeliveryCostStore costStore;
    private PartnerInvoiceService partnerInvoices;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID courierA;
    private UUID courierB;
    private UUID providerBindingId;

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

        jdbc.sql("""
                TRUNCATE TABLE fulfillment.partner_delivery_invoice_lines,
                    fulfillment.partner_delivery_invoices, fulfillment.delivery_cost_lines,
                    fulfillment.delivery_fee_resolutions, fulfillment.shipments,
                    fulfillment.delivery_plans, fulfillment.courier_engagements,
                    fulfillment.couriers, fulfillment.courier_types,
                    ordering.orders, ordering.carts, pricing.quotes, catalog.publications,
                    catalog.catalogs, tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(RESOLVED_AT, ZoneOffset.UTC);
        var store = new JdbcReportingStore(jdbc);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);
        costStore = new JdbcDeliveryCostStore(jdbc);
        partnerInvoices = new PartnerInvoiceService(costStore, fact -> {}, clock);

        seedTenancy();
        seedCouriers();
    }

    // ----------------------------------------------------------------- 7.4b

    @Test
    @DisplayName("the tariff audit sums final_fee_minor per (tariff, courier) over the range")
    void tariffAuditGroupsByTariffAndCourier() {
        UUID orderA = seedOrderAndShipment(courierA, "INTERNAL", "ASSIGNED", null);
        UUID orderB = seedOrderAndShipment(courierB, "INTERNAL", "ASSIGNED", null);
        seedFeeResolution(orderA, TARIFF, 1, 15_000);
        seedFeeResolution(orderA, TARIFF, 1, 18_000);
        seedFeeResolution(orderB, TARIFF, 1, 12_000);

        var result = queries.tariffAudit(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(2);
        var courierARow = result.rows().stream()
                .filter(row -> courierA.equals(row.courierId()))
                .findFirst()
                .orElseThrow();
        assertThat(courierARow.resolutionCount()).isEqualTo(2);
        assertThat(courierARow.totalFinalFeeMinor()).isEqualTo(33_000L);

        var courierBRow = result.rows().stream()
                .filter(row -> courierB.equals(row.courierId()))
                .findFirst()
                .orElseThrow();
        assertThat(courierBRow.resolutionCount()).isEqualTo(1);
        assertThat(courierBRow.totalFinalFeeMinor()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("a resolution outside the range is excluded")
    void tariffAuditExcludesResolutionsOutsideRange() {
        UUID orderA = seedOrderAndShipment(courierA, "INTERNAL", "ASSIGNED", null);
        seedFeeResolution(orderA, TARIFF, 1, 15_000);

        var result = queries.tariffAudit(TENANT, DAY.plusDays(1), DAY.plusDays(1), List.of());

        assertThat(result.rows()).isEmpty();
    }

    // ----------------------------------------------------------------- 7.4c

    @Test
    @DisplayName("a shipment with no invoice line reads UNBILLED and its null variance is excluded from the total")
    void aShipmentWithNoInvoiceLineIsUnbilled() {
        seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);

        var result = queries.externalDeliveryCost(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().matchStatus()).isNull();
        assertThat(result.rows().getFirst().varianceMinor()).isNull();
    }

    @Test
    @DisplayName("MATCHED and VARIANCE lines carry through, and the response total excludes the UNBILLED row")
    void theResponseTotalExcludesUnbilledVariance() {
        UUID matchedOrder = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);
        UUID varianceOrder = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);
        seedOrderAndShipment(courierB, "PARTNER", "DELIVERED", RESOLVED_AT); // UNBILLED, no line at all

        UUID matchedShipment = shipmentIdOf(matchedOrder);
        UUID varianceShipment = shipmentIdOf(varianceOrder);
        seedInvoiceLine(matchedShipment, 20_000, MatchStatus.MATCHED, null);
        seedInvoiceLine(varianceShipment, 25_000, MatchStatus.VARIANCE, 5_000L);

        var result = queries.externalDeliveryCost(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().stream().map(JdbcReportingStore.ExternalDeliveryCostRow::matchStatus))
                .containsExactlyInAnyOrder("MATCHED", "VARIANCE", null);
        // Only the VARIANCE row's 5_000 contributes; the UNBILLED row's null
        // variance is never summed as though it were zero variance -- the same
        // rule CourierReportController.externalDeliveryCost applies to compute
        // totalVarianceMinor, reproduced here directly against the query result.
        long totalVarianceMinor = result.rows().stream()
                .filter(row -> row.varianceMinor() != null)
                .mapToLong(row -> row.varianceMinor())
                .sum();
        assertThat(totalVarianceMinor).isEqualTo(5_000L);
    }

    @Test
    @DisplayName(
            "reconcileShipment marks a VARIANCE line MATCHED, and audits (without a status write) a genuinely unbilled shipment")
    void reconcileShipmentClosesOutAVarianceLineAndAcknowledgesUnbilled() {
        UUID varianceOrder = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);
        UUID unbilledOrder = seedOrderAndShipment(courierB, "PARTNER", "DELIVERED", RESOLVED_AT);
        UUID varianceShipment = shipmentIdOf(varianceOrder);
        UUID unbilledShipment = shipmentIdOf(unbilledOrder);
        seedInvoiceLine(varianceShipment, 25_000, MatchStatus.VARIANCE, 5_000L);

        boolean reconciledVariance =
                partnerInvoices.reconcileShipment(TENANT, varianceShipment, manager(), "operator confirmed the charge");
        boolean reconciledUnbilled =
                partnerInvoices.reconcileShipment(TENANT, unbilledShipment, manager(), "checked, still unbilled");

        assertThat(reconciledVariance).isTrue();
        assertThat(reconciledUnbilled).isFalse();

        var result = queries.externalDeliveryCost(TENANT, DAY, DAY, List.of());
        var varianceRow = result.rows().stream()
                .filter(row -> varianceShipment.equals(row.shipmentId()))
                .findFirst()
                .orElseThrow();
        assertThat(varianceRow.matchStatus()).isEqualTo("MATCHED");
    }

    // --------------------------------------------------------------- fixture

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'tariff-audit-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type,
                    base_url, is_production, egress_allowlist)
                VALUES ('tariff-audit-fixture', 'DELIVERY', 'NOOR', 'https://noor.example', true, '')
                ON CONFLICT (code) DO NOTHING
                """).update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status)
                VALUES (:id, :tenantId, 'DELIVERY', 'NOOR', 'tariff-audit-fixture', 'Noor', 'ACTIVE')
                """).param("id", installationId).param("tenantId", TENANT).update();
        providerBindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", providerBindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .update();
    }

    /** Real service calls, not a hand-built row: {@code protected_full_name} is envelope-encrypted (ADR 0029). */
    private void seedCouriers() {
        var courierStore = new JdbcCourierStore(jdbc);
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
        var engagements = new CourierEngagementService(
                courierStore,
                protection,
                fact -> {},
                policyResolver,
                (t, a) -> false,
                Clock.fixed(RESOLVED_AT, ZoneOffset.UTC));

        UUID courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId, TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, 0, "SHIFT", "ACTIVE", 1));
        courierA = seedCourier(engagements, courierTypeId, "K-A");
        courierB = seedCourier(engagements, courierTypeId, "K-B");
    }

    private UUID seedCourier(CourierEngagementService engagements, UUID courierTypeId, String code) {
        var registration = engagements.register(new CourierEngagementService.NewCourier(
                TENANT,
                courierTypeId,
                "keycloak-" + code,
                code,
                "Courier " + code,
                DAY,
                manager(),
                "onboarding",
                "corr"));
        engagements.verify(new CourierEngagementService.VerifyRegistration(
                TENANT,
                registration.engagementId(),
                "31234567890" + code.charAt(code.length() - 1),
                DAY.plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted",
                "corr"));
        return registration.courierId();
    }

    /** An order with a plan+shipment, in the given source/status; delivered_at set only when deliveredAt is given. */
    private UUID seedOrderAndShipment(UUID courierId, String sourceType, String status, @Nullable Instant deliveredAt) {
        UUID orderId = seedOrder();
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(RESOLVED_AT, ZoneOffset.UTC);

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
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("anchor", anchor)
                .update();

        boolean partner = "PARTNER".equals(sourceType);
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, provider_binding_id, provider_type, assigned_at, delivered_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId, :status, :sourceType,
                        :courierId, :providerBindingId, :providerType, :anchor, :deliveredAt)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("status", status)
                .param("sourceType", sourceType)
                // A PARTNER shipment carries no courier_id (ck_shipment_internal_courier);
                // the tariff audit's own INTERNAL fixture still needs one to join on.
                .param("courierId", partner ? null : courierId)
                .param("providerBindingId", partner ? providerBindingId : null)
                .param("providerType", partner ? "NOOR" : null)
                .param("anchor", anchor)
                .param(
                        "deliveredAt",
                        deliveredAt == null ? null : OffsetDateTime.ofInstant(deliveredAt, ZoneOffset.UTC))
                .update();

        if (partner) {
            // ck_cost_line_internal_courier: a PARTNER cost line names no courier
            // (courier_id is the INTERNAL path's own column) -- the provider is
            // who was paid, per ADR 0042's two-path model.
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_cost_lines (id, tenant_id, shipment_id, business_date,
                        cost_path, cost_basis, amount_minor, currency, source_type, provider_code,
                        recorded_by)
                    VALUES (:id, :tenantId, :shipmentId, :day, 'PARTNER', 'ACCRUED', 20000, 'UZS',
                        'partner_booking:DELIVERY', 'NOOR', 'fixture')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("shipmentId", shipmentId)
                    .param("day", DAY)
                    .update();
        }

        shipmentByOrder.put(orderId, shipmentId);
        return orderId;
    }

    private final java.util.Map<UUID, UUID> shipmentByOrder = new java.util.HashMap<>();

    private UUID shipmentIdOf(UUID orderId) {
        return java.util.Objects.requireNonNull(
                shipmentByOrder.get(orderId), () -> "No shipment seeded for " + orderId);
    }

    private void seedFeeResolution(UUID orderId, UUID tariffId, int tariffVersion, long finalFeeMinor) {
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_fee_resolutions (id, tenant_id, quote_id, location_id,
                    resolution_version, outcome, zone_id, zone_version, tariff_id, tariff_version,
                    band_sequence, distance_meters, distance_mode, distance_source, currency,
                    computed_fee_minor, final_fee_minor, created_at)
                SELECT :id, :tenantId, o.pricing_quote_id, :locationId, 1, 'RESOLVED', :zoneId, 1,
                       :tariffId, :tariffVersion, 1, 3500, 'ROAD', 'ROAD', 'UZS', :feeMinor, :feeMinor, :createdAt
                  FROM ordering.orders o
                 WHERE o.id = :orderId
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("locationId", branch)
                .param("zoneId", ZONE)
                .param("tariffId", tariffId)
                .param("tariffVersion", tariffVersion)
                .param("feeMinor", finalFeeMinor)
                .param("createdAt", OffsetDateTime.ofInstant(RESOLVED_AT, ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();
    }

    /**
     * {@code insertInvoiceLine} only ever writes a fresh {@code PENDING} line
     * (matching {@code PartnerInvoiceService.importInvoice}'s own shape); a
     * line reaches {@code MATCHED}/{@code VARIANCE} only via {@code
     * matchLine}, the same two-step real matching goes through.
     */
    private UUID seedInvoiceLine(UUID shipmentId, long amountMinor, MatchStatus status, @Nullable Long varianceMinor) {
        UUID invoiceId = UUID.randomUUID();
        costStore.insertInvoice(new JdbcDeliveryCostStore.InvoiceRow(
                invoiceId,
                TENANT,
                "NOOR",
                "INV-" + invoiceId,
                null,
                DAY,
                DAY,
                amountMinor,
                "UZS",
                "IMPORTED",
                "fixture"));
        UUID lineId = UUID.randomUUID();
        costStore.insertInvoiceLine(new JdbcDeliveryCostStore.InvoiceLineRow(
                lineId,
                TENANT,
                invoiceId,
                "provider-ref-" + shipmentId,
                null,
                amountMinor,
                "UZS",
                PartnerChargeType.DELIVERY,
                MatchStatus.PENDING,
                null,
                null));
        if (status != MatchStatus.PENDING) {
            costStore.matchLine(
                    TENANT,
                    lineId,
                    shipmentId,
                    status,
                    varianceMinor,
                    status == MatchStatus.VARIANCE ? "AMOUNT_DIFFERS_FROM_BOOKING" : null);
        }
        return lineId;
    }

    private UUID seedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        String reference = "tariff-" + orderId;

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
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS', 'ACTIVE',
                        :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, total_minor, fee_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT', :reference,
                        'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'COMPLETED', 'UZS', 40000, 0, 45000, 5000,
                        :quoteId, 'hash', :publicationId, :cartId, :reference, 1, now())
                """)
                .param("id", orderId)
                .param("number", "TA-" + orderId.toString().substring(0, 8))
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

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Manager");
    }
}
