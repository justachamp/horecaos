package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link DayCloseScheduler} is the production caller ADR 0043's status line says
 * {@link DayCloseService} has never had. These prove the two things that matter:
 * a day the clock says is over gets closed without anyone calling it by hand,
 * and the query genre that answered empty before the close ran now answers a
 * real number — plus the durable claim that keeps two replicas from closing the
 * same day twice.
 */
class DayCloseSchedulerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000c001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000c002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-00000000c003");
    private static final UUID ENTITY = UUID.fromString("018f6f4e-2000-7000-8000-00000000c004");
    private static final UUID CUSTOMER = UUID.fromString("018f6f4e-2000-7000-8000-00000000c005");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    // Exactly the start of DAY in Tashkent, so the heartbeat has no earlier
    // backlog day to catch up on first: DAY is the first business date this
    // tenant ever has.
    private static final Instant TENANT_CREATED_AT = Instant.parse("2026-08-20T19:00:00Z");

    /** Midnight Tashkent the day after {@link #DAY}: the instant the business day ends. */
    private static final Instant DAY_END = Instant.parse("2026-08-21T19:00:00Z");

    private static final Duration CLOSE_DELAY = Duration.ofHours(1);
    private static final Duration SETTLE_WINDOW = Duration.ofDays(1);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private BusinessDayService businessDays;
    private UUID channelId;
    private UUID publicationId;

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

        jdbc.sql("TRUNCATE TABLE reporting.day_close_claims, reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line,
                    reporting.fact_refund, reporting.agg_branch_day, reporting.agg_sla_bucket_day,
                    reporting.business_day_policies, reporting.metric_definitions
                """).update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        businessDays = new BusinessDayService(store);

        new MetricDefinitionSynchronizer(store).synchronizeAll();
        seedTenancy();
    }

    // ----------------------------------------------------------- the proof

    @Test
    void aDayThatHasEndedAndSettledGetsClosedByTheHeartbeatAndTheQueryAnswersNonEmpty() {
        insertOrder("A-1", tashkent(13, 0), tashkent(13, 40), 120_000, 0);

        // Five minutes past business-day-end plus the one-hour close delay: due.
        Instant now = DAY_END.plus(CLOSE_DELAY).plusSeconds(300);
        DayCloseScheduler scheduler = schedulerAt(now);

        scheduler.closeDueDays();

        assertThat(store.lastRunDate(TENANT, "CLOSE"))
                .as("the heartbeat is the caller ADR 0043 says DayCloseService never had")
                .contains(DAY);

        List<java.util.Map<String, Object>> factRows = jdbc.sql(
                        "SELECT order_id FROM reporting.fact_order WHERE tenant_id = :t AND business_date = :d")
                .param("t", TENANT)
                .param("d", DAY)
                .query()
                .listOfRows();
        assertThat(factRows)
                .as("a fact row exists without anyone calling DayCloseService by hand")
                .hasSize(1);

        // The exact gap ADR 0043 names: "every query answers empty". Prove it does not.
        ReportQueryService queries = new ReportQueryService(store, businessDays, Clock.fixed(now, ZoneOffset.UTC));
        ReportQueryService.ReportResult result = queries.run(
                new ReportQuery(TENANT, DAY, DAY, List.of("orders.count.v1"), List.of(), List.of(), List.of()));
        long orders = result.rows().stream()
                .map(row -> row.values().get("orders.count.v1"))
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        assertThat(orders)
                .as("the previously-always-empty query genre now answers non-empty")
                .isEqualTo(1L);
    }

    @Test
    void aDayThatHasNotYetSettledIsLeftAlone() {
        insertOrder("A-1", tashkent(13, 0), tashkent(13, 40), 120_000, 0);

        // One minute short of business-day-end plus the close delay: not yet due.
        Instant now = DAY_END.plus(CLOSE_DELAY).minusSeconds(60);
        DayCloseScheduler scheduler = schedulerAt(now);

        scheduler.closeDueDays();

        assertThat(store.lastRunDate(TENANT, "CLOSE")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.fact_order WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aSettledDayIsRecutOnceAndTheStoredFigureIsUntouchedWhenItAgrees() {
        insertOrder("A-1", tashkent(13, 0), tashkent(13, 40), 120_000, 0);

        Instant closeAt = DAY_END.plus(CLOSE_DELAY).plusSeconds(60);
        schedulerAt(closeAt).closeDueDays();
        assertThat(store.lastRunDate(TENANT, "CLOSE")).contains(DAY);

        Instant recutAt = DAY_END.plus(CLOSE_DELAY).plus(SETTLE_WINDOW).plusSeconds(60);
        DayCloseScheduler scheduler = schedulerAt(recutAt);
        scheduler.recutSettledDays();

        assertThat(store.lastRunDate(TENANT, "RECUT"))
                .as("the settle recut runs a day after close, per ADR 0043's freshness section")
                .contains(DAY);
        assertThat(store.readOpenDivergences(TENANT))
                .as("nothing changed between close and recut, so nothing should diverge")
                .isEmpty();
    }

    // ------------------------------------------------------- the durable claim

    @Test
    void twoReplicasCannotBothClaimTheSameDayAndAnExpiredLeaseCanBeReclaimed() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        UUID replicaA = UUID.randomUUID();
        UUID replicaB = UUID.randomUUID();

        assertThat(store.tryClaimDayClose(TENANT, DAY, "CLOSE", replicaA, now, Duration.ofSeconds(30)))
                .as("the first replica to ask claims the day")
                .isTrue();
        assertThat(store.tryClaimDayClose(TENANT, DAY, "CLOSE", replicaB, now, Duration.ofSeconds(30)))
                .as("a second replica racing the same day must not also succeed")
                .isFalse();

        store.releaseDayCloseClaim(TENANT, DAY, "CLOSE", replicaA);
        assertThat(store.tryClaimDayClose(TENANT, DAY, "CLOSE", replicaB, now, Duration.ofSeconds(30)))
                .as("once released, another replica may claim the same day")
                .isTrue();

        // A replica that died holding the lease does not lock the day out forever.
        Instant afterLeaseExpiry = now.plusSeconds(3600);
        UUID replicaC = UUID.randomUUID();
        assertThat(store.tryClaimDayClose(TENANT, DAY, "CLOSE", replicaC, afterLeaseExpiry, Duration.ofSeconds(30)))
                .as("an expired lease is reclaimable rather than a permanent lock")
                .isTrue();
    }

    // ------------------------------------------------------------------ setup

    private DayCloseScheduler schedulerAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DayCloseService dayClose =
                new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock);
        return new DayCloseScheduler(
                store,
                dayClose,
                businessDays,
                clock,
                new SimpleMeterRegistry(),
                CLOSE_DELAY,
                SETTLE_WINDOW,
                LEASE_DURATION,
                31);
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version, created_at)
                VALUES (:id, 'dayclose-scheduler-tenant', 'Legal', 'Osh Markazi', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0, :createdAt)
                """)
                .param("id", TENANT)
                .param("createdAt", TENANT_CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'ENTITY-A', 'Birinchi MCHJ', '123456789', 'ACTIVE')
                """).param("id", ENTITY).param("t", TENANT).update();

        channelId = UUID.nameUUIDFromBytes("channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("publication".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    private void insertOrder(String seed, Instant createdAt, Instant closedAt, long totalMinor, long discountMinor) {
        UUID orderId = UUID.nameUUIDFromBytes(("order:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID cartId = UUID.nameUUIDFromBytes(("cart:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("quote:" + seed).getBytes(StandardCharsets.UTF_8));
        long subtotal = totalMinor + discountMinor;

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at,
                    converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED', :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                    customer_account_id, currency, status, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                    discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    :subtotal, 0, 0, :discount, :total, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
                .param("subtotal", subtotal)
                .param("discount", discountMinor)
                .param("total", totalMinor)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("accepted", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, discount_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, promised_at, promise_basis, promise_prep_minutes,
                    version, created_at, confirmed_at, closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'TELEGRAM', :cust,
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE',
                    'COMPLETED', 'UZS', :subtotal, 0, :discount, 0,
                    :total, :quote, :hash, :pub,
                    :cart, :key, null, 'NOT_PROMISED', null,
                    1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("subtotal", subtotal)
                .param("discount", discountMinor)
                .param("total", totalMinor)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", createdAt.plusSeconds(120).atOffset(ZoneOffset.UTC))
                .param("closedAt", closedAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, requested_amount_minor,
                    currency, status, capture_timing, idempotency_key, created_at, settled_at)
                VALUES (gen_random_uuid(), :t, :orderId, :b, :loc, :entity, 'CASH', 'CASH',
                    :amount, 'UZS', 'PAID', 'ON_HANDOVER', :key, :createdAt, :createdAt)
                """)
                .param("t", TENANT)
                .param("orderId", orderId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("entity", ENTITY)
                .param("amount", totalMinor)
                .param("key", "intent-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Instant tashkent(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(TASHKENT).toInstant();
    }

    /** Only {@code lookupHash} is exercised: reporting encrypts nothing. */
    private static final class StubProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            throw new UnsupportedOperationException("Reporting stores no protected values");
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            throw new UnsupportedOperationException("Reporting reveals nothing");
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString((tenantId + "|" + lookupDomain + "|" + normalizedValue).hashCode());
        }
    }
}
