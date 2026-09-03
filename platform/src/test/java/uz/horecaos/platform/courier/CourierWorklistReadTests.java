package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.courier.domain.SettlementPeriodStatus;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The three fleet-wide worklist reads added this wave for Finance 8.3
 * (cash handovers), 8.4 (partner delivery invoices), and 8.5 (settlement
 * periods) — {@link JdbcCourierShiftStore#listHandovers},
 * {@link JdbcCourierLedgerStore#listPeriods}, and
 * {@link JdbcDeliveryCostStore#listInvoices}.
 *
 * <p>Rows are inserted straight into the fulfillment.* tables rather than
 * driven through the ADR 0042 services: those services' own transitions are
 * proven by {@code CourierCompensationTests}, and these three methods only
 * ever read what a transition already wrote.
 *
 * <p>Every fixture id is derived from {@code (tenantId, seed)} rather than
 * shared across calls: {@code ux_shift_one_live} permits only one live shift
 * per courier, so a test needing several simultaneous handovers needs
 * several couriers, not several shifts on one.
 */
class CourierWorklistReadTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e0ff");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCourierShiftStore shiftStore;
    private JdbcCourierLedgerStore ledgerStore;
    private JdbcDeliveryCostStore costStore;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.partner_delivery_invoices CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_cash_handovers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_settlement_periods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_shifts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        shiftStore = new JdbcCourierShiftStore(jdbc);
        ledgerStore = new JdbcCourierLedgerStore(jdbc);
        costStore = new JdbcDeliveryCostStore(jdbc);

        seedTenancy(TENANT);
        seedTenancy(OTHER_TENANT);
    }

    @Test
    void handoversSortPendingAndDeclaredAheadOfSettledOutcomes() {
        ShiftFixture a = seedShift(TENANT, "A");
        ShiftFixture b = seedShift(TENANT, "B");
        ShiftFixture c = seedShift(TENANT, "C");
        insertHandover(TENANT, a, "CONFIRMED", 50_000L);
        insertHandover(TENANT, b, "PENDING", 90_000L);
        insertHandover(TENANT, c, "DECLARED", 200_000L);

        List<JdbcCourierShiftStore.HandoverRow> rows = shiftStore.listHandovers(TENANT, null, null, 100);

        assertThat(rows)
                .extracting(JdbcCourierShiftStore.HandoverRow::status)
                .containsExactly("PENDING", "DECLARED", "CONFIRMED");
    }

    @Test
    void handoverStatusAndLocationFiltersNarrowTheWorklist() {
        ShiftFixture a = seedShift(TENANT, "FILTER-A");
        insertHandover(TENANT, a, "PENDING", 10_000L);
        ShiftFixture b = seedShift(TENANT, "FILTER-B");
        insertHandover(TENANT, b, "CONFIRMED", 20_000L);

        List<JdbcCourierShiftStore.HandoverRow> pending = shiftStore.listHandovers(TENANT, "PENDING", null, 100);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo("PENDING");

        List<JdbcCourierShiftStore.HandoverRow> atLocation =
                shiftStore.listHandovers(TENANT, null, locationIdFor(TENANT), 100);
        assertThat(atLocation).hasSize(2);
    }

    @Test
    void handoversNeverCrossTenants() {
        ShiftFixture mine = seedShift(TENANT, "MINE");
        insertHandover(TENANT, mine, "PENDING", 10_000L);
        ShiftFixture theirs = seedShift(OTHER_TENANT, "THEIRS");
        insertHandover(OTHER_TENANT, theirs, "PENDING", 999_000L);

        List<JdbcCourierShiftStore.HandoverRow> rows = shiftStore.listHandovers(TENANT, null, null, 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).expectedMinor()).isEqualTo(10_000L);
    }

    @Test
    void periodsSortClosedAheadOfSettledAndOpenByLargestAmountPayable() {
        insertPeriod(TENANT, "P1", LocalDate.of(2026, 8, 1), "OPEN", 0, 0, 0);
        insertPeriod(TENANT, "P2", LocalDate.of(2026, 8, 8), "CLOSED", 300_000, 0, 0);
        insertPeriod(TENANT, "P3", LocalDate.of(2026, 8, 15), "CLOSED", 900_000, 0, 0);
        insertPeriod(TENANT, "P4", LocalDate.of(2026, 8, 22), "SETTLED", 150_000, 0, 0);

        List<JdbcCourierLedgerStore.PeriodRow> rows = ledgerStore.listPeriods(TENANT, null, 100);

        assertThat(rows)
                .extracting(JdbcCourierLedgerStore.PeriodRow::amountPayableMinor)
                .containsExactly(900_000L, 300_000L, 150_000L, 0L);
        assertThat(rows)
                .extracting(JdbcCourierLedgerStore.PeriodRow::status)
                .containsExactly(
                        SettlementPeriodStatus.CLOSED,
                        SettlementPeriodStatus.CLOSED,
                        SettlementPeriodStatus.SETTLED,
                        SettlementPeriodStatus.OPEN);
    }

    @Test
    void periodStatusFilterNarrowsTheWorklist() {
        insertPeriod(TENANT, "P1", LocalDate.of(2026, 8, 1), "OPEN", 0, 0, 0);
        insertPeriod(TENANT, "P2", LocalDate.of(2026, 8, 8), "CLOSED", 100_000, 0, 0);

        List<JdbcCourierLedgerStore.PeriodRow> closed =
                ledgerStore.listPeriods(TENANT, SettlementPeriodStatus.CLOSED, 100);

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).status()).isEqualTo(SettlementPeriodStatus.CLOSED);
    }

    @Test
    void periodsNeverCrossTenants() {
        insertPeriod(TENANT, "MINE", LocalDate.of(2026, 8, 1), "OPEN", 0, 0, 0);
        insertPeriod(OTHER_TENANT, "THEIRS", LocalDate.of(2026, 8, 1), "OPEN", 0, 0, 0);

        List<JdbcCourierLedgerStore.PeriodRow> rows = ledgerStore.listPeriods(TENANT, null, 100);

        assertThat(rows).hasSize(1);
    }

    @Test
    void invoicesSortImportedAheadOfMatchedByLargestTotal() {
        insertInvoice(TENANT, "yandex-1", "IMPORTED", 400_000L);
        insertInvoice(TENANT, "yandex-2", "IMPORTED", 900_000L);
        insertInvoice(TENANT, "yandex-3", "MATCHED", 5_000_000L);

        List<JdbcDeliveryCostStore.InvoiceRow> rows = costStore.listInvoices(TENANT, null, 100);

        assertThat(rows)
                .extracting(JdbcDeliveryCostStore.InvoiceRow::providerInvoiceRef)
                .containsExactly("yandex-2", "yandex-1", "yandex-3");
    }

    @Test
    void invoiceStatusFilterAndTenantIsolationBothHold() {
        insertInvoice(TENANT, "mine", "IMPORTED", 100_000L);
        insertInvoice(TENANT, "mine-matched", "MATCHED", 50_000L);
        insertInvoice(OTHER_TENANT, "theirs", "IMPORTED", 999_000L);

        List<JdbcDeliveryCostStore.InvoiceRow> imported = costStore.listInvoices(TENANT, "IMPORTED", 100);

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).providerInvoiceRef()).isEqualTo("mine");
    }

    // ----------------------------------------------------------------- fixtures

    private record ShiftFixture(UUID shiftId, UUID courierId) {}

    private record CourierFixture(UUID courierId, UUID engagementId) {}

    private static UUID deriveId(UUID tenantId, String kind) {
        return UUID.nameUUIDFromBytes((kind + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID brandIdFor(UUID tenantId) {
        return deriveId(tenantId, "brand");
    }

    private static UUID locationIdFor(UUID tenantId) {
        return deriveId(tenantId, "location");
    }

    private static UUID courierTypeIdFor(UUID tenantId) {
        return deriveId(tenantId, "courier-type");
    }

    private void seedTenancy(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "courier-worklist-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandIdFor(tenantId))
                .param("tenantId", tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationIdFor(tenantId))
                .param("tenantId", tenantId)
                .param("brandId", brandIdFor(tenantId))
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'SCOOTER', 'Scooter', 'SCOOTER')
                """)
                .param("id", courierTypeIdFor(tenantId))
                .param("tenantId", tenantId)
                .update();
    }

    /** A fresh courier and engagement, keyed by {@code seed} — never shared across calls. */
    private CourierFixture seedCourier(UUID tenantId, String seed) {
        UUID courierId = deriveId(tenantId, "courier:" + seed);
        UUID engagementId = deriveId(tenantId, "engagement:" + seed);
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'protected')
                """)
                .param("id", courierId)
                .param("tenantId", tenantId)
                .param("typeId", courierTypeIdFor(tenantId))
                .param("subject", "courier-subject-" + tenantId + "-" + seed)
                .param("reference", ("K-" + seed).substring(0, Math.min(32, ("K-" + seed).length())))
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements
                    (id, tenant_id, courier_id, engagement_type, status, engaged_from)
                VALUES (:id, :tenantId, :courierId, 'SELF_EMPLOYED', 'PENDING_VERIFICATION', :engagedFrom)
                """)
                .param("id", engagementId)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("engagedFrom", LocalDate.of(2026, 1, 1))
                .update();
        return new CourierFixture(courierId, engagementId);
    }

    private ShiftFixture seedShift(UUID tenantId, String seed) {
        CourierFixture courier = seedCourier(tenantId, seed);
        UUID shiftId = deriveId(tenantId, "shift:" + seed);
        jdbc.sql("""
                INSERT INTO fulfillment.courier_shifts
                    (id, tenant_id, brand_id, location_id, courier_id, engagement_id, status,
                     opened_at, enforcement_mode)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :engagementId, 'OPEN',
                        :openedAt, 'OFF')
                """)
                .param("id", shiftId)
                .param("tenantId", tenantId)
                .param("brandId", brandIdFor(tenantId))
                .param("locationId", locationIdFor(tenantId))
                .param("courierId", courier.courierId())
                .param("engagementId", courier.engagementId())
                .param("openedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return new ShiftFixture(shiftId, courier.courierId());
    }

    private void insertHandover(UUID tenantId, ShiftFixture shift, String status, long expectedMinor) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_cash_handovers
                    (id, tenant_id, shift_id, courier_id, location_id, status, currency, expected_minor)
                VALUES (:id, :tenantId, :shiftId, :courierId, :locationId, :status, 'UZS', :expected)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("shiftId", shift.shiftId())
                .param("courierId", shift.courierId())
                .param("locationId", locationIdFor(tenantId))
                .param("status", status)
                .param("expected", expectedMinor)
                .update();
    }

    private void insertPeriod(
            UUID tenantId, String seed, LocalDate start, String status, long gross, long adjustments, long cashHeld) {
        CourierFixture courier = seedCourier(tenantId, "period-" + seed);

        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("courierId", courier.courierId());
        params.put("engagementId", courier.engagementId());
        params.put("start", start);
        params.put("end", start.plusDays(6));
        params.put("status", status);
        params.put("gross", gross);
        params.put("adjustments", adjustments);
        params.put("cashHeld", cashHeld);
        params.put("payable", gross + adjustments - cashHeld);
        params.put("statementHash", "CLOSED".equals(status) || "SETTLED".equals(status) ? "a".repeat(64) : null);
        params.put(
                "closedAt",
                "CLOSED".equals(status) || "SETTLED".equals(status)
                        ? Instant.now().atOffset(ZoneOffset.UTC)
                        : null);
        params.put("closedBy", "CLOSED".equals(status) || "SETTLED".equals(status) ? "test-fixture" : null);
        params.put("settledAt", "SETTLED".equals(status) ? Instant.now().atOffset(ZoneOffset.UTC) : null);

        jdbc.sql("""
                INSERT INTO fulfillment.courier_settlement_periods (
                    id, tenant_id, courier_id, engagement_id, period_start, period_end, status,
                    currency, gross_earnings_minor, adjustments_minor, cash_held_minor,
                    amount_payable_minor, statement_hash, closed_by, closed_at, settled_at)
                VALUES (
                    :id, :tenantId, :courierId, :engagementId, :start, :end, :status,
                    'UZS', :gross, :adjustments, :cashHeld,
                    :payable, :statementHash, :closedBy, :closedAt, :settledAt)
                """).params(params).update();
    }

    private void insertInvoice(UUID tenantId, String invoiceRef, String status, long totalMinor) {
        jdbc.sql("""
                INSERT INTO fulfillment.partner_delivery_invoices (
                    id, tenant_id, provider_code, provider_invoice_ref, period_start, period_end,
                    total_minor, currency, status, imported_by)
                VALUES (
                    :id, :tenantId, 'YANDEX', :ref, :start, :end, :total, 'UZS', :status, 'test-fixture')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("ref", invoiceRef)
                .param("start", LocalDate.of(2026, 8, 1))
                .param("end", LocalDate.of(2026, 8, 7))
                .param("total", totalMinor)
                .param("status", status)
                .update();
    }
}
