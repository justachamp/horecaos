package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0017's reservation lifecycle and availability projection, over a real
 * PostgreSQL database and the production classes — no stand-in for
 * {@link InventoryService} or {@link JdbcInventoryStore} — in the genre {@code
 * CartCheckoutAndOrderTests} already established for this schema.
 *
 * <p>Written for the gap the ADR's own implementation status names: {@code
 * expireStaleReservations} existed with no caller, no {@code @Scheduled} job,
 * and no test class under this package at all. {@link
 * uz.horecaos.platform.RowLevelSecurityBackstopTests} already proves the
 * row-level-security shape of the cross-tenant sweep once V0162 is on; this
 * class proves the sweep's own contract — a hold taken, committed, released,
 * left to lapse, or left alone — and the one row-level-security property that
 * suite does not: that a session bound to a single tenant, not the platform
 * exemption, can only ever reach that tenant's own rows.
 *
 * <p>Per this repository's own rule that a fixture's clock is the test's
 * clock: {@link #aLapsedHoldIsSweptOnceTheClockPassesItsTtl()} and {@link
 * #theSweepLeavesAStillLiveHoldAlone()} advance a {@link MutableClock} across
 * the real {@link InventoryService#RESERVATION_TTL} rather than inserting a
 * reservation whose {@code expires_at} was already in the past — a TTL
 * asserted without moving time proves nothing about the duration, only about
 * an instant somebody chose.
 */
class InventoryReservationAndAvailabilityTests {

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcInventoryStore store;
    private MutableClock clock;
    private InventoryService inventory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wireService() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        // The database is shared across every test method in this class (one
        // TestDatabase.Handle per class, not per test), and expireReservations
        // is a deliberately cross-tenant sweep with no tenant predicate of its
        // own -- so a HELD row a previous test left behind, however unrelated
        // its tenant, is visible to this test's own sweep call. The tenant
        // isolation test below leaves exactly such a row on purpose (a still-
        // live hold, to prove a sweep does not touch it); truncating first is
        // what keeps that intentional leftover from being counted by whichever
        // test happens to run next.
        jdbc.sql("""
                        TRUNCATE TABLE inventory.reservation_lines, inventory.reservations,
                            inventory.movements, inventory.positions, inventory.stock_items CASCADE
                        """).update();
        store = new JdbcInventoryStore(jdbc);
        clock = new MutableClock(Instant.parse("2026-09-05T09:00:00Z"));
        inventory = new InventoryService(store, event -> {}, clock);
    }

    // -----------------------------------------------------------------------
    // A hold taken, then settled one way or the other
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a hold taken for a quote commits into a sale, and cannot be committed twice")
    void aHeldReservationCanBeCommitted() {
        Fixture fixture = seedBinaryFixture();
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventory.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant(),
                Map.of(fixture.variantId(), 1)));
        UUID reservationId = requireHeldId(held);
        assertThat(reservationStatus(reservationId)).isEqualTo("HELD");

        boolean committed = tx(() -> inventory.commit(fixture.tenantId(), quoteId));
        assertThat(committed).as("a live hold commits into a sale").isTrue();
        assertThat(reservationStatus(reservationId)).isEqualTo("COMMITTED");

        // The store's WHERE status = 'HELD' predicate is what a retried
        // confirmation relies on: a second commit call must change nothing.
        boolean committedAgain = tx(() -> inventory.commit(fixture.tenantId(), quoteId));
        assertThat(committedAgain)
                .as("a terminal reservation cannot be committed a second time")
                .isFalse();
        assertThat(reservationStatus(reservationId)).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("a hold taken for an abandoned cart can be released back")
    void aHeldReservationCanBeReleased() {
        Fixture fixture = seedBinaryFixture();
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventory.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant(),
                Map.of(fixture.variantId(), 1)));
        UUID reservationId = requireHeldId(held);

        boolean released = tx(() -> inventory.release(fixture.tenantId(), quoteId));
        assertThat(released).as("a live hold releases back").isTrue();
        assertThat(reservationStatus(reservationId)).isEqualTo("RELEASED");

        boolean releasedAgain = tx(() -> inventory.release(fixture.tenantId(), quoteId));
        assertThat(releasedAgain)
                .as("a terminal reservation cannot be released a second time")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // The sweep: only what is due, and only because time actually passed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a hold that outlives its TTL is swept once the clock actually passes it")
    void aLapsedHoldIsSweptOnceTheClockPassesItsTtl() {
        Fixture fixture = seedBinaryFixture();
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventory.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant(),
                Map.of(fixture.variantId(), 1)));
        UUID reservationId = requireHeldId(held);

        // The customer never comes back. Advancing the clock past the real
        // fifteen-minute TTL is what makes this a duration rather than an
        // instant somebody hand-picked to already be in the past.
        clock.advance(InventoryService.RESERVATION_TTL.plusSeconds(1));

        int expiredCount = tx(inventory::expireStaleReservations);

        assertThat(expiredCount).as("exactly the one lapsed hold is swept").isEqualTo(1);
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("the sweep leaves a hold alone while it is still inside its TTL")
    void theSweepLeavesAStillLiveHoldAlone() {
        Fixture fixture = seedBinaryFixture();
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventory.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant(),
                Map.of(fixture.variantId(), 1)));
        UUID reservationId = requireHeldId(held);

        // Five minutes of a fifteen-minute TTL: comfortably still live.
        clock.advance(Duration.ofMinutes(5));

        int expiredCount = tx(inventory::expireStaleReservations);

        assertThat(expiredCount).as("nothing has reached its TTL yet").isZero();
        assertThat(reservationStatus(reservationId))
                .as("a hold still inside its TTL must survive the sweep, not merely be excluded from its count")
                .isEqualTo("HELD");
    }

    // -----------------------------------------------------------------------
    // Availability: an unlisted variant, and a sold-out one
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("availability refuses a variant nobody listed at this location")
    void availabilityRefusesAnUnlistedVariant() {
        Fixture fixture = seedTenantBrandLocationVariant();
        // Deliberately no listVariantAtLocation call: this variant has no stock
        // item at this location at all.

        AvailabilityDecision decision = tx(() ->
                inventory.checkAvailability(fixture.tenantId(), fixture.locationId(), Set.of(fixture.variantId())));

        assertThat(decision.available())
                .as("an unlisted variant is unavailable, not available by default")
                .isFalse();
        assertThat(decision.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("NOT_STOCKED_AT_LOCATION");
    }

    @Test
    @DisplayName("availability refuses a binary variant once it is toggled sold out")
    void availabilityRefusesABinaryVariantToggledSoldOut() {
        Fixture fixture = seedBinaryFixture();

        boolean availableAtStart = tx(() -> inventory
                .checkAvailability(fixture.tenantId(), fixture.locationId(), Set.of(fixture.variantId()))
                .available());
        assertThat(availableAtStart)
                .as("a freshly listed binary item starts available")
                .isTrue();

        tx(() -> inventory.setAvailability(
                fixture.tenantId(), fixture.locationId(), fixture.variantId(), false, "SOLD_OUT", null));

        AvailabilityDecision decision = tx(() ->
                inventory.checkAvailability(fixture.tenantId(), fixture.locationId(), Set.of(fixture.variantId())));

        assertThat(decision.available()).as("the 86 toggle takes effect").isFalse();
        assertThat(decision.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("SOLD_OUT");
    }

    // -----------------------------------------------------------------------
    // Tenant isolation: a single-tenant-bound sweep reaches only that tenant
    // -----------------------------------------------------------------------

    /**
     * {@code JdbcInventoryStore.expireReservations} carries no {@code WHERE
     * tenant_id = ?} at all (see that method) — it is a deliberately global
     * bulk update, safe only because {@link
     * InventoryService#expireStaleReservations()} always calls it through
     * {@link TenantRlsSession#bindPlatform()}. This test asks the other
     * question: what does the identical call see if it is ever run through an
     * ordinary tenant binding instead — by a bug, a bad merge, or a
     * "simplification" that pushes the exemption somewhere it should not be.
     * The answer has to be "only that tenant's own rows", enforced by V0162's
     * row-level security as the backstop the query's own SQL does not
     * provide.
     *
     * <p>Runs over a throw-away login role holding nothing but {@code
     * horecaos_application} (plus, for the broken-code demonstration this test
     * is designed to catch, {@code horecaos_platform_bypass}) — the same shape
     * {@code RowLevelSecurityBackstopTests} uses and for the same reason: a
     * row-level-security claim proven from the migrator's own connection
     * proves nothing, because PostgreSQL exempts a table's owner from its own
     * policies automatically.
     */
    @Test
    @DisplayName("a sweep bound to one tenant reaches only that tenant's own due hold")
    void aTenantBoundSweepDoesNotReachAnotherTenantsRows() {
        String probeRole = "inv_sweep_isolation_probe";
        String probePassword = "inv-sweep-isolation-probe";

        Fixture tenantA = seedTenantBrandLocationVariant();
        Fixture tenantB = seedTenantBrandLocationVariant();

        Instant due = clock.instant().minusSeconds(60);
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        store.insertReservation(
                reservationA,
                tenantA.tenantId(),
                tenantA.brandId(),
                tenantA.locationId(),
                "QUOTE",
                UUID.randomUUID(),
                due,
                due.minusSeconds(60));
        store.insertReservation(
                reservationB,
                tenantB.tenantId(),
                tenantB.brandId(),
                tenantB.locationId(),
                "QUOTE",
                UUID.randomUUID(),
                due,
                due.minusSeconds(60));

        createLoginRole(probeRole, probePassword);
        try (HikariDataSource probeDataSource = (HikariDataSource) db.dataSourceAs(probeRole, probePassword)) {
            JdbcClient probeJdbc = JdbcClient.create(probeDataSource);
            TenantRlsSession probeRls = new JdbcTenantRlsSession(probeJdbc);
            JdbcInventoryStore probeStore = new JdbcInventoryStore(probeJdbc);
            TransactionTemplate probeTx = new TransactionTemplate(new DataSourceTransactionManager(probeDataSource));

            List<UUID> sweptWhileBoundToA = probeTx.execute(status -> {
                probeRls.bindTenant(tenantA.tenantId());
                return probeStore.expireReservations(clock.instant());
            });

            assertThat(sweptWhileBoundToA)
                    .as("bound to tenant A alone, this call may only ever touch tenant A's own due hold")
                    .containsExactly(reservationA);
        } finally {
            jdbc.sql("DROP ROLE IF EXISTS " + probeRole).update();
        }

        assertThat(reservationStatus(reservationA))
                .as("tenant A's own due hold is reached")
                .isEqualTo("EXPIRED");
        assertThat(reservationStatus(reservationB))
                .as("row-level security — not a WHERE clause this query does not have — is what keeps a "
                        + "tenant-bound sweep from reaching a different tenant's equally-due hold")
                .isEqualTo("HELD");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record Fixture(UUID tenantId, UUID brandId, UUID locationId, UUID variantId) {}

    /** A stock item at a fresh tenant/brand/location, listed BINARY and starting available. */
    private Fixture seedBinaryFixture() {
        Fixture fixture = seedTenantBrandLocationVariant();
        UUID stockItemId = tx(() -> inventory.listVariantAtLocation(
                fixture.tenantId(), fixture.brandId(), fixture.locationId(), fixture.variantId(), TrackingMode.BINARY));
        assertThat(stockItemId)
                .as("listing a variant returns the new stock item's id")
                .isNotNull();
        return fixture;
    }

    /** The tenant/brand/location/product/variant chain {@code inventory.*}'s foreign keys require. */
    private Fixture seedTenantBrandLocationVariant() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);

        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone, status)
                        VALUES (:id, :slug, 'Inventory test', 'Inventory test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                        """).param("id", tenantId).param("slug", "inv-" + suffix).update();

        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                        VALUES (:id, :tenantId, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                        """).param("id", brandId).param("tenantId", tenantId).update();

        jdbc.sql("""
                        INSERT INTO tenant.locations (
                            id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                        VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Location', 'Asia/Tashkent', 'ACTIVE')
                        """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        jdbc.sql("""
                        INSERT INTO catalog.products (id, tenant_id, brand_id, code)
                        VALUES (:id, :tenantId, :brandId, 'SKU')
                        """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        jdbc.sql("""
                        INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id)
                        VALUES (:id, :tenantId, :brandId, :productId)
                        """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();

        return new Fixture(tenantId, brandId, locationId, variantId);
    }

    private void createLoginRole(String name, String password) {
        jdbc.sql("DROP ROLE IF EXISTS " + name).update();
        jdbc.sql("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'").update();
        jdbc.sql("ALTER ROLE " + name + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        jdbc.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + name).update();
        // Not exercised by the passing path of aTenantBoundSweepDoesNotReachAnotherTenantsRows,
        // which never calls bindPlatform -- granted anyway so a deliberate,
        // reverted break of that test (temporarily having expireReservations
        // assume the exempt role itself, regardless of what its caller bound)
        // fails on a leaked row rather than on a permission error, which would
        // prove nothing about isolation either way.
        jdbc.sql("GRANT horecaos_platform_bypass TO " + name + " WITH INHERIT FALSE")
                .update();
    }

    /**
     * Asserts a hold succeeded and returns its id as {@code @NonNull} — {@link
     * ReservationResult#reservationId()} is {@code @Nullable} only because a
     * refusal carries none, and every call site here has already committed to
     * "this hold succeeded" before it needs the id for a follow-up call.
     */
    private static UUID requireHeldId(ReservationResult result) {
        assertThat(result.isHeld())
                .as("stock is listed and available, so the hold succeeds")
                .isTrue();
        return java.util.Objects.requireNonNull(result.reservationId());
    }

    private String reservationStatus(UUID reservationId) {
        return jdbc.sql("SELECT status FROM inventory.reservations WHERE id = :id")
                .param("id", reservationId)
                .query(String.class)
                .single();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> work.run());
    }

    /** A clock the test advances, so TTL and sweep behaviour is asserted against a duration, not an instant. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
