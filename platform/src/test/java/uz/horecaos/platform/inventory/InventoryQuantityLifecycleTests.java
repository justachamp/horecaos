package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.BusinessDayWindows;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0017's QUANTITY branch (gap map row 4.4c) over a real PostgreSQL
 * database and the production classes, in the genre {@code
 * InventoryReservationAndAvailabilityTests} and {@code WalletConcurrencyTests}
 * already established.
 *
 * <p>Five properties, none of which a single-threaded, mocked-store test can
 * show:
 *
 * <ul>
 *   <li>two concurrent holds for the last unit of stock never both succeed
 *       (real threads, real Postgres — {@link
 *       #twoConcurrentHoldsForTheLastUnitLeaveExactlyOneReserved});
 *   <li>a release only ever touches a {@code HELD} reservation, never a
 *       {@code COMMITTED} one (cancellation restock stays undecided — ADR
 *       0017's own open input — rather than silently answered either way);
 *   <li>a reservation, commit or release for one tenant's quote id can never
 *       reach a different tenant's stock, even when both tenants happen to
 *       use the identical quote id;
 *   <li>the daily reset applies exactly once per (stock item, business
 *       date), on the tenant's own boundary, not the UTC calendar; and
 *   <li>a per-channel-type stop threshold cuts an aggregator off early while
 *       the plain, channel-less check keeps selling to zero.
 * </ul>
 */
class InventoryQuantityLifecycleTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcInventoryStore store;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private InventoryService inventoryOn;

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
        jdbc.sql("""
                        TRUNCATE TABLE inventory.channel_stop_thresholds, inventory.reservation_lines,
                            inventory.reservations, inventory.movements, inventory.positions,
                            inventory.stock_items CASCADE
                        """).update();
        store = new JdbcInventoryStore(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(Instant.parse("2026-09-20T09:00:00Z"));
        // catalog.use_stock_logic on: this whole suite is about real
        // enforcement, not the off/UNTRACKED behaviour InventoryQuantityUseStockLogicGateTests covers.
        inventoryOn = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)));
    }

    // -----------------------------------------------------------------------
    // Concurrent holds cannot oversell
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two concurrent holds for the last unit leave exactly one reserved")
    void twoConcurrentHoldsForTheLastUnitLeaveExactlyOneReserved() throws Exception {
        Fixture fixture = seedQuantityFixture(BigDecimal.ONE);
        UUID quoteA = UUID.randomUUID();
        UUID quoteB = UUID.randomUUID();

        List<Outcome<ReservationResult>> outcomes = bothAtOnce(
                () -> inventoryOn.reserveForQuote(
                        fixture.tenantId(),
                        fixture.brandId(),
                        fixture.locationId(),
                        quoteA,
                        clock.instant().plusSeconds(900),
                        Map.of(fixture.variantId(), 1)),
                () -> inventoryOn.reserveForQuote(
                        fixture.tenantId(),
                        fixture.brandId(),
                        fixture.locationId(),
                        quoteB,
                        clock.instant().plusSeconds(900),
                        Map.of(fixture.variantId(), 1)));

        long held = outcomes.stream()
                .filter(Outcome::succeeded)
                .filter(outcome -> outcome.requireValue().isHeld())
                .count();
        assertThat(held)
                .as("one unit of true stock, two racing holds for it — exactly one may win")
                .isEqualTo(1);

        assertThat(reservedQuantity(fixture.stockItemId()))
                .as("the position never records more reserved than the one unit actually held")
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    // -----------------------------------------------------------------------
    // Release only ever touches HELD; commit is what actually moves stock
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("commit reduces on-hand and reserved together, with a SALE_COMMITMENT movement")
    void commitReducesOnHandAndReservedTogether() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(5));
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 2)));
        assertThat(held.isHeld()).isTrue();
        assertThat(reservedQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(2));

        boolean committed = tx(() -> inventoryOn.commit(fixture.tenantId(), quoteId));

        assertThat(committed).isTrue();
        assertThat(onHandQuantity(fixture.stockItemId()))
                .as("committing is the sale — on-hand actually drops")
                .isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(reservedQuantity(fixture.stockItemId()))
                .as("and the hold that became the sale is no longer reserved")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(movementTypes(fixture.stockItemId())).contains("SALE_COMMITMENT");
    }

    @Test
    @DisplayName("a commit that floors on-hand below what it removes still leaves the ledger reconcilable")
    void commitFlooringWhenOnHandWasCorrectedBelowReservedStaysReconcilableWithTheLedger() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(5));
        UUID quoteId = UUID.randomUUID();

        ReservationResult held = tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 3)));
        assertThat(held.isHeld()).isTrue();

        // Spoilage discovered after the hold was taken. setOnHandQuantity never
        // refuses for going below reserved_quantity (its own doc), so on-hand
        // legitimately ends up below what is already reserved.
        tx(() -> inventoryOn.setOnHandQuantity(
                fixture.tenantId(),
                fixture.locationId(),
                fixture.variantId(),
                BigDecimal.valueOf(2),
                "SPOILAGE",
                "op-1"));
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(reservedQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(ledgerSum(fixture.stockItemId()))
                .as("sanity check: the ledger already reconciles with on-hand before the commit")
                .isEqualByComparingTo(onHandQuantity(fixture.stockItemId()));

        boolean committed = tx(() -> inventoryOn.commit(fixture.tenantId(), quoteId));
        assertThat(committed).isTrue();

        assertThat(onHandQuantity(fixture.stockItemId()))
                .as("the position stays floored at zero, never negative")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reservedQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerSum(fixture.stockItemId()))
                .as("summing inventory.movements must still equal on-hand after a commit that had to floor — "
                        + "a bare SALE_COMMITMENT for the full, un-floored quantity would leave the ledger "
                        + "permanently diverged from the position with no trace of the shortfall")
                .isEqualByComparingTo(onHandQuantity(fixture.stockItemId()));
    }

    @Test
    @DisplayName("release gives reserved back without ever touching on-hand, and cannot reach a COMMITTED reservation")
    void releaseTouchesReservedOnlyAndNeverACommittedReservation() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(5));
        UUID heldQuote = UUID.randomUUID();
        UUID committedQuote = UUID.randomUUID();

        tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                heldQuote,
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 2)));
        tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                committedQuote,
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 1)));
        tx(() -> inventoryOn.commit(fixture.tenantId(), committedQuote));
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(4));

        boolean releasedHeld = tx(() -> inventoryOn.release(fixture.tenantId(), heldQuote));
        assertThat(releasedHeld).isTrue();
        assertThat(reservedQuantity(fixture.stockItemId()))
                .as("only the still-HELD hold's 2 units come back")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(onHandQuantity(fixture.stockItemId()))
                .as("release never restocks — on-hand is exactly what the commit above already sold")
                .isEqualByComparingTo(BigDecimal.valueOf(4));

        boolean releasedCommitted = tx(() -> inventoryOn.release(fixture.tenantId(), committedQuote));
        assertThat(releasedCommitted)
                .as("a COMMITTED reservation is not HELD, so release refuses it — cancellation-after-commit "
                        + "restock is ADR 0017's own open input, not answered here either way")
                .isFalse();
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(4));
    }

    @Test
    @DisplayName("a lapsed hold is expired by the sweep, releasing reserved only")
    void expirySweepReleasesReservedOnly() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(3));
        UUID quoteId = UUID.randomUUID();
        tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                quoteId,
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 2)));
        assertThat(reservedQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(2));

        clock.advance(java.time.Duration.ofMinutes(16));
        int expired = tx(inventoryOn::expireStaleReservations);

        assertThat(expired).isEqualTo(1);
        assertThat(reservedQuantity(fixture.stockItemId()))
                .as("the expiry sweep is a release, not a restock")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    // -----------------------------------------------------------------------
    // Tenant isolation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the identical quote id for two different tenants never lets one reach the other's stock")
    void tenantIsolationHoldsEvenForTheIdenticalQuoteId() {
        Fixture tenantA = seedQuantityFixture(BigDecimal.ONE);
        Fixture tenantB = seedQuantityFixture(BigDecimal.ONE);
        UUID sharedQuoteId = UUID.randomUUID();

        tx(() -> inventoryOn.reserveForQuote(
                tenantA.tenantId(),
                tenantA.brandId(),
                tenantA.locationId(),
                sharedQuoteId,
                clock.instant().plusSeconds(900),
                Map.of(tenantA.variantId(), 1)));

        boolean crossTenantCommit = tx(() -> inventoryOn.commit(tenantB.tenantId(), sharedQuoteId));
        boolean crossTenantRelease = tx(() -> inventoryOn.release(tenantB.tenantId(), sharedQuoteId));

        assertThat(crossTenantCommit)
                .as("tenant B has no reservation under this quote id — tenant A's is invisible to it")
                .isFalse();
        assertThat(crossTenantRelease).isFalse();
        assertThat(reservedQuantity(tenantA.stockItemId()))
                .as("tenant A's own hold is completely untouched by tenant B's failed calls")
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    // -----------------------------------------------------------------------
    // V0405/V0406/V0407 grant the application role what it needs
    // -----------------------------------------------------------------------

    /**
     * {@code horecaos_application} — never the migrator, whose connection
     * bypasses every {@code GRANT} as the schema's own owner — can read and
     * write everything the QUANTITY branch added: V0405's {@code
     * stock_items.default_quantity}, V0406's {@code
     * positions.last_reset_business_date}, and V0407's whole new table,
     * {@code inventory.channel_stop_thresholds}. The same probe-role shape
     * {@code InventoryReservationAndAvailabilityTests
     * .aTenantBoundSweepDoesNotReachAnotherTenantsRows} uses, and for the
     * identical reason: a privilege claim proven from the migrator's own
     * connection proves nothing, because PostgreSQL exempts a table's owner
     * from its own policies and grants alike.
     */
    @Test
    @DisplayName("V0405/V0406/V0407's new columns and table are granted to horecaos_application")
    void newQuantityColumnsAndTheChannelStopThresholdTableAreGranted() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(5));
        String probeRole = "inv_quantity_grant_probe";
        String probePassword = "inv-quantity-grant-probe";

        createLoginRole(probeRole, probePassword);
        try (com.zaxxer.hikari.HikariDataSource probeDataSource =
                (com.zaxxer.hikari.HikariDataSource) db.dataSourceAs(probeRole, probePassword)) {
            JdbcClient probeJdbc = JdbcClient.create(probeDataSource);
            TransactionTemplate probeTx = new TransactionTemplate(new DataSourceTransactionManager(probeDataSource));

            probeTx.executeWithoutResult(status -> {
                new uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession(probeJdbc)
                        .bindTenant(fixture.tenantId());

                probeJdbc
                        .sql("UPDATE inventory.stock_items SET default_quantity = 9 WHERE id = :id")
                        .param("id", fixture.stockItemId())
                        .update();
                probeJdbc
                        .sql("UPDATE inventory.positions SET last_reset_business_date = '2026-09-20' "
                                + "WHERE stock_item_id = :id")
                        .param("id", fixture.stockItemId())
                        .update();
                probeJdbc
                        .sql("""
                                INSERT INTO inventory.channel_stop_thresholds (
                                    id, tenant_id, brand_id, location_id, stock_item_id, channel_system_type,
                                    stop_at_or_below)
                                VALUES (:id, :tenantId, :brandId, :locationId, :stockItemId, 'AGGREGATOR', 2)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", fixture.tenantId())
                        .param("brandId", fixture.brandId())
                        .param("locationId", fixture.locationId())
                        .param("stockItemId", fixture.stockItemId())
                        .update();
                Long thresholdCount = probeJdbc
                        .sql("SELECT count(*) FROM inventory.channel_stop_thresholds WHERE stock_item_id = :id")
                        .param("id", fixture.stockItemId())
                        .query(Long.class)
                        .single();
                assertThat(thresholdCount).isEqualTo(1L);
                probeJdbc
                        .sql("DELETE FROM inventory.channel_stop_thresholds WHERE stock_item_id = :id")
                        .param("id", fixture.stockItemId())
                        .update();
            });
        } finally {
            jdbc.sql("DROP ROLE IF EXISTS " + probeRole).update();
        }
    }

    private void createLoginRole(String name, String password) {
        jdbc.sql("DROP ROLE IF EXISTS " + name).update();
        jdbc.sql("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'").update();
        jdbc.sql("ALTER ROLE " + name + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        jdbc.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + name).update();
    }

    // -----------------------------------------------------------------------
    // Daily reset at the tenant's business-day boundary
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the daily reset applies once per business date and is idempotent within the same date")
    void dailyResetAppliesOncePerBusinessDateAndIsIdempotent() {
        Fixture fixture = seedQuantityFixture(BigDecimal.ZERO);
        tx(() -> inventoryOn.setDefaultQuantity(
                fixture.tenantId(),
                fixture.locationId(),
                fixture.variantId(),
                BigDecimal.TEN,
                "DAILY_DEFAULT",
                "op-1"));

        FakeBusinessDayWindows businessDays = new FakeBusinessDayWindows(LocalDate.of(2026, 9, 20));
        InventoryService inventoryWithBusinessDays = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)),
                businessDays);

        int resetCount = tx(() -> inventoryWithBusinessDays.resetDueQuantityItems(fixture.tenantId(), clock.instant()));
        assertThat(resetCount).isEqualTo(1);
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(movementTypes(fixture.stockItemId())).contains("CORRECTION");

        // An operator sells three of the ten before the next business day.
        tx(() -> inventoryOn.setOnHandQuantity(
                fixture.tenantId(), fixture.locationId(), fixture.variantId(), BigDecimal.valueOf(7), "SOLD", "op-1"));

        // Same business date again: the scheduler ticking twice in one day
        // (or a second replica) must not reset a second time.
        int againSameDate =
                tx(() -> inventoryWithBusinessDays.resetDueQuantityItems(fixture.tenantId(), clock.instant()));
        assertThat(againSameDate).isZero();
        assertThat(onHandQuantity(fixture.stockItemId()))
                .as("no second reset within the same business date")
                .isEqualByComparingTo(BigDecimal.valueOf(7));

        // The next business day: due again.
        businessDays.set(LocalDate.of(2026, 9, 21));
        int nextDate = tx(() -> inventoryWithBusinessDays.resetDueQuantityItems(fixture.tenantId(), clock.instant()));
        assertThat(nextDate).isEqualTo(1);
        assertThat(onHandQuantity(fixture.stockItemId())).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    @DisplayName(
            "an item with no default quantity is never listed as due, and the tenant worklist finds only configured items")
    void tenantsWithQuantityDefaultsFindsOnlyConfiguredItems() {
        Fixture withDefault = seedQuantityFixture(BigDecimal.ZERO);
        Fixture withoutDefault = seedQuantityFixture(BigDecimal.ZERO);
        tx(() -> inventoryOn.setDefaultQuantity(
                withDefault.tenantId(),
                withDefault.locationId(),
                withDefault.variantId(),
                BigDecimal.valueOf(4),
                "DAILY_DEFAULT",
                "op-1"));

        List<UUID> due = tx(inventoryOn::tenantsWithQuantityDefaults);

        assertThat(due).contains(withDefault.tenantId());
        assertThat(due).doesNotContain(withoutDefault.tenantId());
    }

    // -----------------------------------------------------------------------
    // Per-channel-type stop threshold
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a channel-type threshold stops that channel early while the plain check keeps selling to zero")
    void channelThresholdStopsEarlyWhilePlainCheckSellsToZero() {
        // 3 remaining, a threshold that stops AGGREGATOR at "3 or fewer left"
        // -- the kitchen's own buffer against a marketplace order it cannot
        // actually fulfil by the time it prints.
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(3));
        tx(() -> inventoryOn.setChannelStopThreshold(
                fixture.tenantId(),
                fixture.locationId(),
                fixture.variantId(),
                "AGGREGATOR",
                BigDecimal.valueOf(3),
                "KITCHEN_BUFFER",
                "op-1"));

        AvailabilityDecision aggregatorView = tx(() -> inventoryOn.checkAvailabilityForChannel(
                fixture.tenantId(), fixture.locationId(), java.util.Set.of(fixture.variantId()), "AGGREGATOR"));
        AvailabilityDecision plainView = tx(() -> inventoryOn.checkAvailability(
                fixture.tenantId(), fixture.locationId(), java.util.Set.of(fixture.variantId())));
        AvailabilityDecision webView = tx(() -> inventoryOn.checkAvailabilityForChannel(
                fixture.tenantId(), fixture.locationId(), java.util.Set.of(fixture.variantId()), "WEB"));

        assertThat(aggregatorView.available())
                .as("3 remaining, threshold 3: AGGREGATOR stops here even though true stock remains")
                .isFalse();
        assertThat(aggregatorView.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("CHANNEL_STOPPED");
        assertThat(plainView.available())
                .as("the reservation/hold path's own check carries no channel and is unaffected")
                .isTrue();
        assertThat(webView.available())
                .as("a channel type with no threshold configured keeps selling to zero, like today")
                .isTrue();

        // The hold path itself never refuses on the threshold, only on true
        // stock exhaustion -- it is a projection-only cutoff.
        ReservationResult held = tx(() -> inventoryOn.reserveForQuote(
                fixture.tenantId(),
                fixture.brandId(),
                fixture.locationId(),
                UUID.randomUUID(),
                clock.instant().plusSeconds(900),
                Map.of(fixture.variantId(), 2)));
        assertThat(held.isHeld())
                .as("2 <= 3 true stock: the reservation itself is not gated by the AGGREGATOR threshold")
                .isTrue();
    }

    @Test
    @DisplayName("clearing a channel threshold makes that channel sell to zero again")
    void clearingAThresholdRestoresSellingToZero() {
        Fixture fixture = seedQuantityFixture(BigDecimal.valueOf(5));
        tx(() -> inventoryOn.setChannelStopThreshold(
                fixture.tenantId(),
                fixture.locationId(),
                fixture.variantId(),
                "AGGREGATOR",
                BigDecimal.valueOf(3),
                "KITCHEN_BUFFER",
                "op-1"));

        boolean cleared = tx(() -> inventoryOn.clearChannelStopThreshold(
                fixture.tenantId(),
                fixture.locationId(),
                fixture.variantId(),
                "AGGREGATOR",
                "NO_LONGER_NEEDED",
                "op-1"));
        assertThat(cleared).isTrue();

        AvailabilityDecision aggregatorView = tx(() -> inventoryOn.checkAvailabilityForChannel(
                fixture.tenantId(), fixture.locationId(), java.util.Set.of(fixture.variantId()), "AGGREGATOR"));
        assertThat(aggregatorView.available()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record Fixture(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID stockItemId) {}

    /** A QUANTITY stock item at a fresh tenant/brand/location, with the given on-hand quantity. */
    private Fixture seedQuantityFixture(BigDecimal onHand) {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);

        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone, status)
                        VALUES (:id, :slug, 'Inventory quantity test', 'Inventory quantity test', 'UZS',
                            'Asia/Tashkent', 'ACTIVE')
                        """).param("id", tenantId).param("slug", "invq-" + suffix).update();
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

        UUID stockItemId = tx(() ->
                inventoryOn.listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY));
        if (onHand.signum() > 0) {
            tx(() -> inventoryOn.setOnHandQuantity(tenantId, locationId, variantId, onHand, "INITIAL_COUNT", "op-1"));
        }
        return new Fixture(tenantId, brandId, locationId, variantId, stockItemId);
    }

    private BigDecimal onHandQuantity(UUID stockItemId) {
        return jdbc.sql("SELECT on_hand_quantity FROM inventory.positions WHERE stock_item_id = :id")
                .param("id", stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal reservedQuantity(UUID stockItemId) {
        return jdbc.sql("SELECT reserved_quantity FROM inventory.positions WHERE stock_item_id = :id")
                .param("id", stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private List<String> movementTypes(UUID stockItemId) {
        return jdbc.sql(
                        "SELECT movement_type FROM inventory.movements WHERE stock_item_id = :id ORDER BY sequence_number")
                .param("id", stockItemId)
                .query(String.class)
                .list();
    }

    /** What on_hand_quantity must equal if the ledger is to be reconstructable, per ADR 0017. */
    private BigDecimal ledgerSum(UUID stockItemId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory.movements WHERE stock_item_id = :id")
                .param("id", stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private <T> T tx(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private void tx(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    /**
     * Runs both pieces of work on their own threads, each in its own
     * transaction, released together by a barrier tripped after both
     * transactions have begun — {@code WalletConcurrencyTests.bothAtOnce}'s
     * own shape, copied rather than shared across packages.
     */
    private <T> List<Outcome<T>> bothAtOnce(Supplier<T> left, Supplier<T> right) {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Supplier<T> work : List.of(left, right)) {
                futures.add(threads.submit((Callable<Outcome<T>>) () -> {
                    try {
                        T value = Objects.requireNonNull(transactions.execute(status -> {
                            try {
                                gate.await(30, TimeUnit.SECONDS);
                            } catch (Exception interrupted) {
                                throw new IllegalStateException("the gate never opened", interrupted);
                            }
                            return work.get();
                        }));
                        return new Outcome<>(true, value);
                    } catch (RuntimeException refused) {
                        return new Outcome<>(false, null);
                    }
                }));
            }
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (Exception failed) {
                    throw new IllegalStateException("a racing thread never finished", failed);
                }
            }
            return List.copyOf(outcomes);
        }
    }

    private record Outcome<T>(boolean succeeded, @Nullable T value) {
        /** Only ever called after filtering {@link #succeeded()}, whose true value guarantees this is non-null. */
        T requireValue() {
            return Objects.requireNonNull(value);
        }
    }

    /** A clock the test advances, so TTL/reset behaviour is asserted against a duration, not an instant. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
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

    /** A tenant business date a test moves forward, independent of the wall clock. */
    private static final class FakeBusinessDayWindows implements BusinessDayWindows {
        private volatile LocalDate date;

        private FakeBusinessDayWindows(LocalDate date) {
            this.date = date;
        }

        void set(LocalDate date) {
            this.date = date;
        }

        @Override
        public LocalDate businessDateOf(UUID tenantId, Instant at) {
            return date;
        }
    }
}
