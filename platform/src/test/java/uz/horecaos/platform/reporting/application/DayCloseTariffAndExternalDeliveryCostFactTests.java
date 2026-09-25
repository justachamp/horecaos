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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.domain.MatchStatus;
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
 * w6-reporting-facts, batch 11 (7.4b/7.4c, ADR 0023/0125): {@code
 * reporting.fact_delivery_fee_resolution} (V0411) and {@code
 * reporting.fact_external_delivery_cost} (V0412)'s own close-time producers
 * inside {@link DayCloseService#close} — the projector-level test {@link
 * CourierTariffAuditAndExternalCostTests} deliberately stays out of, the
 * same isolation that file's own class doc states for itself and {@code
 * DayCloseDeliveryFactTests} states for {@code fact_delivery} beside these
 * two.
 *
 * <p>Content, idempotency and tenant isolation only — the read side (what
 * {@code readTariffAudit}/{@code readExternalDeliveryCost} do with these
 * rows) is {@link CourierTariffAuditAndExternalCostTests}'s own job.
 */
class DayCloseTariffAndExternalDeliveryCostFactTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID TARIFF = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final Instant RESOLVED_AT = Instant.parse("2026-09-01T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private DayCloseService close;
    private JdbcDeliveryCostStore costStore;
    private PartnerInvoiceService partnerInvoices;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID courierA;
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

        Clock closeClock = Clock.fixed(RESOLVED_AT.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        var businessDays = new BusinessDayService(store);
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(TestProtection.envelope()), closeClock);
        costStore = new JdbcDeliveryCostStore(jdbc);
        partnerInvoices = new PartnerInvoiceService(costStore, fact -> {}, closeClock);

        seedTenancy();
        seedCouriers();
    }

    // ----------------------------------------------------------------- 7.4b

    @Test
    @DisplayName("close writes one fact_delivery_fee_resolution row from the resolution")
    void closeProducesOneFeeResolutionFact() {
        UUID orderA = seedOrderAndShipment(courierA, "INTERNAL", "ASSIGNED", null);
        UUID resolutionId = seedFeeResolution(orderA, TARIFF, 3, 15_000);

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT resolution_id, location_id, tariff_id, tariff_version, zone_id, band_sequence,
                       courier_id, order_id, shipment_id, final_fee_minor, currency
                  FROM reporting.fact_delivery_fee_resolution
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("resolution_id")).isEqualTo(resolutionId);
        assertThat(row.get("tariff_id")).isEqualTo(TARIFF);
        assertThat(row.get("tariff_version")).isEqualTo(3);
        assertThat(row.get("zone_id")).isEqualTo(ZONE);
        assertThat(row.get("band_sequence")).isEqualTo(1);
        assertThat(row.get("courier_id")).isEqualTo(courierA);
        assertThat(row.get("order_id")).isEqualTo(orderA);
        assertThat(row.get("final_fee_minor")).isEqualTo(15_000L);
        assertThat(row.get("currency")).isEqualTo("UZS");
    }

    @Test
    @DisplayName("re-running close over an unchanged day reproduces the identical fee-resolution row")
    void closeIsIdempotentForFeeResolutionFact() {
        UUID orderA = seedOrderAndShipment(courierA, "INTERNAL", "ASSIGNED", null);
        seedFeeResolution(orderA, TARIFF, 1, 15_000);

        close.close(TENANT, DAY);
        List<Map<String, Object>> first = readFeeResolutionRows();

        close.close(TENANT, DAY);
        List<Map<String, Object>> second = readFeeResolutionRows();

        assertThat(second).isEqualTo(first);
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("close never mixes one tenant's fee-resolution fact into another's")
    void closeNeverCrossesTenantsForFeeResolutionFact() {
        UUID orderA = seedOrderAndShipment(courierA, "INTERNAL", "ASSIGNED", null);
        seedFeeResolution(orderA, TARIFF, 1, 15_000);

        TenantFixture other = seedOtherTenant();
        UUID otherOrder = seedOrderAndShipment(
                other.tenantId(),
                other.brandId(),
                other.locationId(),
                other.channelId(),
                other.publicationId(),
                other.providerBindingId(),
                other.courierId(),
                "INTERNAL",
                "ASSIGNED",
                null);
        seedFeeResolution(other.tenantId(), other.locationId(), otherOrder, TARIFF, 1, 99_000);

        close.close(TENANT, DAY);
        close.close(other.tenantId(), DAY);

        List<Map<String, Object>> tenantRows =
                jdbc.sql("""
                SELECT final_fee_minor FROM reporting.fact_delivery_fee_resolution
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();
        List<Map<String, Object>> otherRows = jdbc.sql("""
                SELECT final_fee_minor FROM reporting.fact_delivery_fee_resolution
                 WHERE tenant_id = :t AND business_date = :d
                """)
                .param("t", other.tenantId())
                .param("d", DAY)
                .query()
                .listOfRows();

        assertThat(tenantRows).hasSize(1);
        assertThat(tenantRows.getFirst().get("final_fee_minor")).isEqualTo(15_000L);
        assertThat(otherRows).hasSize(1);
        assertThat(otherRows.getFirst().get("final_fee_minor")).isEqualTo(99_000L);
    }

    // ----------------------------------------------------------------- 7.4c

    @Test
    @DisplayName("close writes one fact_external_delivery_cost row, carrying whatever matching had reached")
    void closeProducesOneExternalDeliveryCostFact() {
        UUID orderA = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);
        UUID shipmentId = shipmentIdOf(orderA);
        UUID lineId = seedInvoiceLine(shipmentId, 20_000, MatchStatus.MATCHED, null);

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT shipment_id, order_id, currency, charged_delivery_minor, provider_type,
                       provider_estimated_minor, invoice_line_id, provider_billed_minor,
                       match_status, variance_minor
                  FROM reporting.fact_external_delivery_cost
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("shipment_id")).isEqualTo(shipmentId);
        assertThat(row.get("order_id")).isEqualTo(orderA);
        assertThat(row.get("currency")).isEqualTo("UZS");
        assertThat(row.get("charged_delivery_minor")).isEqualTo(5_000L);
        assertThat(row.get("provider_type")).isEqualTo("NOOR");
        assertThat(row.get("provider_estimated_minor")).isEqualTo(20_000L);
        assertThat(row.get("invoice_line_id")).isEqualTo(lineId);
        assertThat(row.get("provider_billed_minor")).isEqualTo(20_000L);
        assertThat(row.get("match_status")).isEqualTo("MATCHED");
        assertThat(row.get("variance_minor")).isNull();
    }

    @Test
    @DisplayName("a shipment with no invoice line writes a fact row with a null match status")
    void closeProducesANullMatchStatusForAnUnbilledShipment() {
        UUID orderA = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT invoice_line_id, match_status FROM reporting.fact_external_delivery_cost
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("invoice_line_id")).isNull();
        assertThat(rows.getFirst().get("match_status")).isNull();
    }

    @Test
    @DisplayName("re-running close over an unchanged day reproduces the identical external-delivery-cost row")
    void closeIsIdempotentForExternalDeliveryCostFact() {
        UUID orderA = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);
        seedInvoiceLine(shipmentIdOf(orderA), 20_000, MatchStatus.MATCHED, null);

        close.close(TENANT, DAY);
        List<Map<String, Object>> first = readExternalDeliveryCostRows();

        close.close(TENANT, DAY);
        List<Map<String, Object>> second = readExternalDeliveryCostRows();

        assertThat(second).isEqualTo(first);
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("close never mixes one tenant's external-delivery-cost fact into another's")
    void closeNeverCrossesTenantsForExternalDeliveryCostFact() {
        UUID orderA = seedOrderAndShipment(courierA, "PARTNER", "DELIVERED", RESOLVED_AT);

        TenantFixture other = seedOtherTenant();
        seedOrderAndShipment(
                other.tenantId(),
                other.brandId(),
                other.locationId(),
                other.channelId(),
                other.publicationId(),
                other.providerBindingId(),
                other.courierId(),
                "PARTNER",
                "DELIVERED",
                RESOLVED_AT);

        close.close(TENANT, DAY);
        close.close(other.tenantId(), DAY);

        List<Map<String, Object>> tenantRows =
                jdbc.sql("""
                SELECT order_id FROM reporting.fact_external_delivery_cost
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();
        List<Map<String, Object>> otherRows = jdbc.sql("""
                SELECT order_id FROM reporting.fact_external_delivery_cost
                 WHERE tenant_id = :t AND business_date = :d
                """)
                .param("t", other.tenantId())
                .param("d", DAY)
                .query()
                .listOfRows();

        assertThat(tenantRows).hasSize(1);
        assertThat(tenantRows.getFirst().get("order_id")).isEqualTo(orderA);
        assertThat(otherRows).hasSize(1);
        assertThat(otherRows.getFirst().get("order_id")).isNotEqualTo(orderA);
    }

    // --------------------------------------------------------------- fixture

    private List<Map<String, Object>> readFeeResolutionRows() {
        return jdbc.sql("""
                SELECT resolution_id, final_fee_minor, courier_id FROM reporting.fact_delivery_fee_resolution
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();
    }

    private List<Map<String, Object>> readExternalDeliveryCostRows() {
        return jdbc.sql("""
                SELECT shipment_id, match_status, provider_billed_minor
                  FROM reporting.fact_external_delivery_cost
                 WHERE tenant_id = :t AND business_date = :d
                """).param("t", TENANT).param("d", DAY).query().listOfRows();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'fee-resolution-fact-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                VALUES ('fee-resolution-fact-fixture', 'DELIVERY', 'NOOR', 'https://noor.example', true, '')
                ON CONFLICT (code) DO NOTHING
                """).update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status)
                VALUES (:id, :tenantId, 'DELIVERY', 'NOOR', 'fee-resolution-fact-fixture', 'Noor', 'ACTIVE')
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

    private void seedCouriers() {
        var courierStore = new JdbcCourierStore(jdbc);
        var protection = TestProtection.envelope();
        var policyResolver = new CourierPolicyResolver(noopPolicyResolver());
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
        courierA = seedCourier(TENANT, engagements, courierTypeId, "K-A");
    }

    private static PolicyResolver noopPolicyResolver() {
        return new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        };
    }

    private UUID seedCourier(UUID tenantId, CourierEngagementService engagements, UUID courierTypeId, String code) {
        var registration = engagements.register(new CourierEngagementService.NewCourier(
                tenantId,
                courierTypeId,
                "keycloak-" + code,
                code,
                "Courier " + code,
                DAY,
                manager(),
                "onboarding",
                "corr"));
        engagements.verify(new CourierEngagementService.VerifyRegistration(
                tenantId,
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

    /**
     * A second, wholly independent tenant — the same isolation shape {@code
     * CourierTariffAuditAndExternalCostTests#seedOtherTenant} already builds
     * and states why.
     */
    private TenantFixture seedOtherTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID catalogId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal 2', 'Display 2', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "fee-fact-other-" + tenantId)
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
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront 2', 'ACTIVE')
                """).param("id", channelId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu 2', 'ACTIVE')
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

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status)
                VALUES (:id, :tenantId, 'DELIVERY', 'NOOR', 'fee-resolution-fact-fixture', 'Noor', 'ACTIVE')
                """).param("id", installationId).param("tenantId", tenantId).update();
        UUID providerBindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", providerBindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .update();

        var courierStore = new JdbcCourierStore(jdbc);
        var protection = TestProtection.envelope();
        var policyResolver = new CourierPolicyResolver(noopPolicyResolver());
        var engagements = new CourierEngagementService(
                courierStore,
                protection,
                fact -> {},
                policyResolver,
                (t, a) -> false,
                Clock.fixed(RESOLVED_AT, ZoneOffset.UTC));
        UUID courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId, tenantId, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, 0, "SHIFT", "ACTIVE", 1));
        UUID courierId = seedCourier(tenantId, engagements, courierTypeId, "K-OTHER");

        return new TenantFixture(tenantId, brandId, locationId, channelId, publicationId, providerBindingId, courierId);
    }

    private record TenantFixture(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID publicationId,
            UUID providerBindingId,
            UUID courierId) {}

    private UUID seedOrderAndShipment(UUID courierId, String sourceType, String status, @Nullable Instant deliveredAt) {
        return seedOrderAndShipment(
                TENANT,
                BRAND,
                branch,
                channelId,
                publicationId,
                providerBindingId,
                courierId,
                sourceType,
                status,
                deliveredAt);
    }

    private UUID seedOrderAndShipment(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID publicationId,
            UUID providerBindingId,
            UUID courierId,
            String sourceType,
            String status,
            @Nullable Instant deliveredAt) {
        UUID orderId = seedOrder(tenantId, brandId, locationId, channelId, publicationId);
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
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
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
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("status", status)
                .param("sourceType", sourceType)
                .param("courierId", partner ? null : courierId)
                .param("providerBindingId", partner ? providerBindingId : null)
                .param("providerType", partner ? "NOOR" : null)
                .param("anchor", anchor)
                .param(
                        "deliveredAt",
                        deliveredAt == null ? null : OffsetDateTime.ofInstant(deliveredAt, ZoneOffset.UTC))
                .update();

        if (partner) {
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_cost_lines (id, tenant_id, shipment_id, business_date,
                        cost_path, cost_basis, amount_minor, currency, source_type, provider_code,
                        recorded_by)
                    VALUES (:id, :tenantId, :shipmentId, :day, 'PARTNER', 'ACCRUED', 20000, 'UZS',
                        'partner_booking:DELIVERY', 'NOOR', 'fixture')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
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

    private UUID seedFeeResolution(UUID orderId, UUID tariffId, int tariffVersion, long finalFeeMinor) {
        return seedFeeResolution(TENANT, branch, orderId, tariffId, tariffVersion, finalFeeMinor);
    }

    private UUID seedFeeResolution(
            UUID tenantId, UUID locationId, UUID orderId, UUID tariffId, int tariffVersion, long finalFeeMinor) {
        UUID resolutionId = UUID.randomUUID();
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
                .param("id", resolutionId)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("zoneId", ZONE)
                .param("tariffId", tariffId)
                .param("tariffVersion", tariffVersion)
                .param("feeMinor", finalFeeMinor)
                .param("createdAt", OffsetDateTime.ofInstant(RESOLVED_AT, ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();
        return resolutionId;
    }

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
                uz.horecaos.platform.courier.domain.PartnerChargeType.DELIVERY,
                MatchStatus.PENDING,
                null,
                null,
                null,
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

    private UUID seedOrder(UUID tenantId, UUID brandId, UUID locationId, UUID channelId, UUID publicationId) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        String reference = "fee-fact-" + orderId;

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
                        :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
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
                .param("number", "FF-" + orderId.toString().substring(0, 8))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();
        return orderId;
    }

    private static uz.horecaos.platform.audit.api.ActorRef manager() {
        return uz.horecaos.platform.audit.api.ActorRef.user("keycloak-manager", "Manager");
    }
}
