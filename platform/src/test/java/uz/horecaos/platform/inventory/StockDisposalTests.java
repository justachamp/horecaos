package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.DisposalRefusedException;
import uz.horecaos.platform.inventory.api.InventoryEvent;
import uz.horecaos.platform.inventory.api.InventoryStockDisposed;
import uz.horecaos.platform.inventory.api.StockDisposal;
import uz.horecaos.platform.inventory.api.StockDisposalCommand;
import uz.horecaos.platform.inventory.api.StockDisposalCommand.Disposition;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0169's fourth ADR 0017 verb, {@code dispose}, over a real PostgreSQL database and the production classes.
 *
 * <p>The property worth a database is that the branch for each unit is taken from the reservations themselves, in
 * one transaction, and never from the order's status: a unit still on a {@code HELD} hold is released and
 * moves nothing, a unit already sold takes the declared disposition, and the window between "confirmed" and
 * "committed" (a few seconds wide, easy to miss) does not double-count when the queued commit runs afterwards.
 *
 * <p>Every ledger assertion is made against the ledger and not against the position it explains: {@code
 * SUM(movements) == on_hand} is the one that a wrong write-off would break, where "on_hand is 3" is true
 * either way.
 */
class StockDisposalTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private InventoryService inventory;
    private final List<InventoryEvent> events = new ArrayList<>();
    private final List<AuditFact> audited = new ArrayList<>();

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
    void wire() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
        events.clear();
        audited.clear();
        inventory = new InventoryService(
                new JdbcInventoryStore(jdbc),
                event -> {
                    if (event instanceof InventoryEvent inventoryEvent) {
                        events.add(inventoryEvent);
                    }
                },
                clock,
                audited::add,
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)));
    }

    // ------------------------------------------------------------------------------ held: a release

    @Test
    @DisplayName(
            "a unit still on a hold is released by reducing the line and the reserved quantity, and writes no movement")
    void aHeldUnitIsReleasedAndMovesNothing() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);

        StockDisposal disposal = dispose(shelf, List.of(quote), 2, 0, Disposition.RETURN_TO_STOCK, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.releasedUnits()).as("both units were still a hold").isEqualTo(2);
        assertThat(line.returnedUnits() + line.writtenOffUnits() + line.noEffectUnits())
                .as("and none of them was sold")
                .isZero();
        assertThat(reserved(shelf))
                .as("reserved comes down by what was released")
                .isEqualByComparingTo("1");
        assertThat(onHand(shelf)).as("a release never touches the shelf").isEqualByComparingTo("5");
        assertThat(reservationLineQuantity(shelf, quote)).isEqualByComparingTo("1");
        assertThat(movementTypes(shelf)).as("a release is not a movement").doesNotContain("RETURN", "WASTE");
        assertThat(ledgerSum(shelf)).isEqualByComparingTo(onHand(shelf));
    }

    // ----------------------------------------------------------------------------- sold: a return

    @Test
    @DisplayName("a committed unit is returned to the shelf with a RETURN movement")
    void aSoldUnitIsReturned() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);
        assertThat(onHand(shelf)).isEqualByComparingTo("2");

        StockDisposal disposal = dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.returnedUnits()).isEqualTo(1);
        assertThat(line.releasedUnits()).as("nothing was left on a hold").isZero();
        assertThat(onHand(shelf)).as("the unit is back on the shelf").isEqualByComparingTo("3");
        assertThat(movementTypes(shelf)).contains("RETURN");
        assertThat(movementDeltas(shelf, "RETURN")).containsExactly(new BigDecimal("1.000"));
        assertThat(line.movementIds())
                .as("and the result names the movement it wrote")
                .hasSize(1);
        assertThat(ledgerSum(shelf))
                .as("the ledger still reconciles with the position")
                .isEqualByComparingTo(onHand(shelf));
    }

    @Test
    @DisplayName(
            "a write-off is a RETURN and then a WASTE, so the sale is reversed, the loss is written and on-hand is net unchanged")
    void aWriteOffIsReturnThenWaste() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);

        StockDisposal disposal = dispose(shelf, List.of(quote), 2, 0, Disposition.WRITE_OFF, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.writtenOffUnits()).isEqualTo(2);
        assertThat(line.returnedUnits()).isZero();
        assertThat(movementDeltas(shelf, "RETURN")).containsExactly(new BigDecimal("2.000"));
        assertThat(movementDeltas(shelf, "WASTE")).containsExactly(new BigDecimal("-2.000"));
        assertThat(onHand(shelf))
                .as("the goods were lost, so the shelf does not grow")
                .isEqualByComparingTo("2");
        assertThat(ledgerSum(shelf))
                .as("and summing the ledger gives the same shelf: a lone WASTE would have removed them twice")
                .isEqualByComparingTo(onHand(shelf));
        assertThat(line.movementIds()).hasSize(2);
    }

    @Test
    @DisplayName("a declaration of no effect counts the unit and writes nothing")
    void noEffectWritesNothing() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 2);
        commit(quote, shelf);

        StockDisposal disposal = dispose(shelf, List.of(quote), 1, 0, Disposition.NO_EFFECT, "d-1");

        assertThat(disposal.lines().get(0).noEffectUnits()).isEqualTo(1);
        assertThat(onHand(shelf)).isEqualByComparingTo("3");
        assertThat(movementTypes(shelf)).doesNotContain("RETURN", "WASTE");
    }

    // ----------------------------------------------------------------- the kitchen: more conservative

    @Test
    @DisplayName("units the kitchen has started or made are written off whatever the declared disposition says")
    void startedUnitsAreWrittenOffWhateverTheReasonDeclares() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);

        StockDisposal disposal = dispose(shelf, List.of(quote), 2, 1, Disposition.RETURN_TO_STOCK, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.writtenOffUnits()).as("the cooked one is lost").isEqualTo(1);
        assertThat(line.returnedUnits()).as("the other follows the reason").isEqualTo(1);
        assertThat(onHand(shelf))
                .as("one went back on the shelf and one did not")
                .isEqualByComparingTo("3");
        assertThat(ledgerSum(shelf)).isEqualByComparingTo(onHand(shelf));
    }

    // ----------------------------------- the window between "confirmed" and "committed" is real

    @Test
    @DisplayName(
            "a unit disposed of before the queued commit runs is released, and the commit then takes only what is left")
    void theCommitThatRunsAfterAReleaseDoesNotCountTheReleasedUnitAgain() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);

        // The order is CONFIRMED and its commit is still queued: the hold is HELD. Taking one unit off now is a
        // release (reserved goes 3 -> 2), not a return: nothing was sold yet.
        StockDisposal disposal = dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "d-1");
        assertThat(disposal.lines().get(0).releasedUnits()).isEqualTo(1);
        assertThat(disposal.lines().get(0).returnedUnits())
                .as("a return here would be subtracted again when the commit runs")
                .isZero();

        commit(quote, shelf);

        assertThat(onHand(shelf))
                .as("the commit sold the two units that remained on the order, not three")
                .isEqualByComparingTo("3");
        assertThat(reserved(shelf)).isEqualByComparingTo("0");
        assertThat(movementDeltas(shelf, "SALE_COMMITMENT")).containsExactly(new BigDecimal("-2.000"));
        assertThat(movementTypes(shelf)).doesNotContain("RETURN");
        assertThat(ledgerSum(shelf)).isEqualByComparingTo(onHand(shelf));
    }

    @Test
    @DisplayName(
            "an order that holds an original hold and an amendment's committed increase gives back from the hold first")
    void anOrderWithAHeldOriginalAndACommittedIncreaseGivesBackFromTheHoldFirst() {
        Shelf shelf = shelf(10);
        UUID original = hold(shelf, 2);
        UUID amendment = hold(shelf, 1);
        commit(amendment, shelf);
        assertThat(onHand(shelf)).isEqualByComparingTo("9");

        StockDisposal disposal = dispose(shelf, List.of(original, amendment), 2, 0, Disposition.RETURN_TO_STOCK, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.releasedUnits()).as("the two held units go first").isEqualTo(2);
        assertThat(line.returnedUnits())
                .as("the committed one is not touched yet")
                .isZero();
        assertThat(onHand(shelf)).isEqualByComparingTo("9");
        assertThat(reserved(shelf)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a hold that has expired is not a claim: the order holds nothing, and the disposal is refused")
    void anExpiredHoldIsNoLongerAClaim() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 2);
        clock.advance(Duration.ofHours(2));
        assertThat(tx(() -> inventory.expireStaleReservations()))
                .as("the sweep is global, so other suites' old holds may expire with this one")
                .isGreaterThanOrEqualTo(1);
        assertThat(reserved(shelf)).as("the sweep gave the hold back").isEqualByComparingTo("0");

        Throwable refused =
                catchThrowable(() -> dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "d-1"));

        assertThat(refused).isInstanceOf(DisposalRefusedException.class);
        assertThat(((DisposalRefusedException) refused).code()).isEqualTo("DISPOSAL_EXCEEDS_ORDER_CLAIM");
        assertThat(onHand(shelf)).isEqualByComparingTo("5");
        assertThat(reserved(shelf)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------------------ untracked items

    @Test
    @DisplayName("an item nothing is counted for gets its counts back and no movement")
    void anUntrackedItemWritesNoMovement() {
        Shelf shelf = shelf(0, TrackingMode.UNTRACKED);
        UUID quote = hold(shelf, 2);
        commit(quote, shelf);

        StockDisposal disposal = dispose(shelf, List.of(quote), 1, 0, Disposition.WRITE_OFF, "d-1");

        StockDisposal.LineResult line = disposal.lines().get(0);
        assertThat(line.writtenOffUnits())
                .as("the count is the record of what the disposal meant")
                .isEqualTo(1);
        assertThat(line.movementIds()).as("but there is no position to move").isEmpty();
        assertThat(movementTypes(shelf)).doesNotContain("RETURN", "WASTE");
    }

    @Test
    @DisplayName("a variant with no stock item at the location is reported as untouched and does not stop the others")
    void aVariantWithNoStockItemIsUntouched() {
        Shelf counted = shelf(5);
        UUID quote = hold(counted, 1);
        commit(quote, counted);
        UUID unlisted = newVariant(counted.tenantId, counted.brandId);

        StockDisposal disposal = tx(() -> inventory.dispose(new StockDisposalCommand(
                counted.tenantId,
                counted.brandId,
                counted.locationId,
                counted.orderId,
                List.of(quote),
                List.of(
                        new StockDisposalCommand.Line(counted.variantId, 1, 0),
                        new StockDisposalCommand.Line(unlisted, 1, 0)),
                Disposition.RETURN_TO_STOCK,
                "CUSTOMER_CHANGED_MIND",
                "SERVICE",
                null,
                "d-1")));

        assertThat(disposal.lines()).hasSize(2);
        assertThat(disposal.lines().stream()
                        .filter(line -> line.variantId().equals(unlisted))
                        .findFirst()
                        .orElseThrow()
                        .noEffectUnits())
                .isEqualTo(1);
        assertThat(onHand(counted)).as("and the listed one was returned").isEqualByComparingTo("5");
    }

    // ----------------------------------------------------------------------------------- idempotency

    @Test
    @DisplayName("a repeat with the same key returns the first answer and moves nothing")
    void aReplayMovesNothing() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);

        StockDisposal first = dispose(shelf, List.of(quote), 1, 0, Disposition.WRITE_OFF, "d-1");
        long movementsAfterFirst = movementCount(shelf);
        BigDecimal onHandAfterFirst = onHand(shelf);

        StockDisposal second = dispose(shelf, List.of(quote), 1, 0, Disposition.WRITE_OFF, "d-1");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.lines().get(0).writtenOffUnits()).isEqualTo(1);
        assertThat(second.lines().get(0).movementIds())
                .as("the same movements the first call wrote")
                .containsExactlyElementsOf(first.lines().get(0).movementIds());
        assertThat(movementCount(shelf)).isEqualTo(movementsAfterFirst);
        assertThat(onHand(shelf)).isEqualByComparingTo(onHandAfterFirst);
        assertThat(events.stream().filter(InventoryStockDisposed.class::isInstance))
                .as("and publishes the fact once")
                .hasSize(1);
    }

    @Test
    @DisplayName("a replay of a disposal that only released a hold releases nothing a second time")
    void aReplayOfAReleaseOnlyDisposalReleasesNothingTwice() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);

        dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "d-1");
        dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "d-1");

        assertThat(reserved(shelf))
                .as("the ledger's own unique key cannot hold a release, which writes no movement; the record can")
                .isEqualByComparingTo("2");
        assertThat(reservationLineQuantity(shelf, quote)).isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------------------------- refusal

    @Test
    @DisplayName("asking for more than the order holds is refused and writes nothing")
    void aDisposalAboveTheOrdersClaimIsRefused() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 2);
        commit(quote, shelf);

        Throwable refused =
                catchThrowable(() -> dispose(shelf, List.of(quote), 3, 0, Disposition.RETURN_TO_STOCK, "d-1"));

        assertThat(refused).isInstanceOf(DisposalRefusedException.class);
        assertThat(((DisposalRefusedException) refused).code()).isEqualTo("DISPOSAL_EXCEEDS_ORDER_CLAIM");
        assertThat(onHand(shelf)).isEqualByComparingTo("3");
        assertThat(movementTypes(shelf)).doesNotContain("RETURN", "WASTE");
        assertThat(disposalCount(shelf)).as("not even the record").isZero();
    }

    @Test
    @DisplayName(
            "units already disposed of are not counted a second time: a removal and then a cancellation return each unit once")
    void earlierDisposalsAreNotCountedTwice() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);

        dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "removal");
        dispose(shelf, List.of(quote), 2, 0, Disposition.RETURN_TO_STOCK, "cancellation");

        assertThat(onHand(shelf)).as("each of the three units came back once").isEqualByComparingTo("5");
        Throwable again =
                catchThrowable(() -> dispose(shelf, List.of(quote), 1, 0, Disposition.RETURN_TO_STOCK, "again"));
        assertThat(again)
                .as("and there is nothing left on the order to give back")
                .isInstanceOf(DisposalRefusedException.class);
        assertThat(ledgerSum(shelf)).isEqualByComparingTo(onHand(shelf));
    }

    @Test
    @DisplayName("two disposals racing for the same order's last units serialise: one wins, one is refused")
    void twoConcurrentDisposalsSerialise() throws Exception {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 2);
        commit(quote, shelf);

        List<Boolean> outcomes = bothAtOnce(
                () -> dispose(shelf, List.of(quote), 2, 0, Disposition.RETURN_TO_STOCK, "race-a"),
                () -> dispose(shelf, List.of(quote), 2, 0, Disposition.RETURN_TO_STOCK, "race-b"));

        assertThat(outcomes.stream().filter(Boolean::booleanValue).count())
                .as("the order held two units: only one of two requests for both may succeed")
                .isEqualTo(1);
        assertThat(onHand(shelf)).as("and they came back once").isEqualByComparingTo("5");
        assertThat(ledgerSum(shelf)).isEqualByComparingTo(onHand(shelf));
    }

    // ------------------------------------------------------------------------ preview and evidence

    @Test
    @DisplayName("the preview says what the disposal will do, and writes nothing")
    void thePreviewMatchesAndWritesNothing() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 3);
        commit(quote, shelf);
        StockDisposalCommand command = command(shelf, List.of(quote), 2, 1, Disposition.RETURN_TO_STOCK, "d-1");

        StockDisposal preview = tx(() -> inventory.previewDispose(command));

        assertThat(preview.lines().get(0).writtenOffUnits()).isEqualTo(1);
        assertThat(preview.lines().get(0).returnedUnits()).isEqualTo(1);
        assertThat(onHand(shelf)).as("a preview moves nothing").isEqualByComparingTo("2");
        assertThat(disposalCount(shelf)).isZero();
        assertThat(movementTypes(shelf)).doesNotContain("RETURN", "WASTE");

        StockDisposal applied = tx(() -> inventory.dispose(command));
        assertThat(applied.lines().get(0).writtenOffUnits())
                .isEqualTo(preview.lines().get(0).writtenOffUnits());
        assertThat(applied.lines().get(0).returnedUnits())
                .isEqualTo(preview.lines().get(0).returnedUnits());
    }

    @Test
    @DisplayName("a disposal is audited with counts and never with an order's customer, and publishes one fact")
    void aDisposalIsAuditedAndPublished() {
        Shelf shelf = shelf(5);
        UUID quote = hold(shelf, 2);
        commit(quote, shelf);

        dispose(shelf, List.of(quote), 1, 0, Disposition.WRITE_OFF, "d-1");

        assertThat(audited).extracting(AuditFact::actionCode).contains("inventory.stock.disposed");
        InventoryStockDisposed event = events.stream()
                .filter(InventoryStockDisposed.class::isInstance)
                .map(InventoryStockDisposed.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(event.orderId()).isEqualTo(shelf.orderId);
        assertThat(event.disposition()).isEqualTo("WRITE_OFF");
        assertThat(event.lines()).hasSize(1);
        assertThat(event.lines().get(0).writtenOffUnits()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------- helpers

    private StockDisposal dispose(
            Shelf shelf, List<UUID> quotes, int units, int started, Disposition declared, String key) {
        return tx(() -> inventory.dispose(command(shelf, quotes, units, started, declared, key)));
    }

    private StockDisposalCommand command(
            Shelf shelf, List<UUID> quotes, int units, int started, Disposition declared, String key) {
        return new StockDisposalCommand(
                shelf.tenantId,
                shelf.brandId,
                shelf.locationId,
                shelf.orderId,
                quotes,
                List.of(new StockDisposalCommand.Line(shelf.variantId, units, started)),
                declared,
                "CUSTOMER_CHANGED_MIND",
                "SERVICE",
                null,
                key);
    }

    private UUID hold(Shelf shelf, int units) {
        UUID quote = UUID.randomUUID();
        var held = tx(() -> inventory.reserveForQuote(
                shelf.tenantId,
                shelf.brandId,
                shelf.locationId,
                quote,
                clock.instant().plusSeconds(900),
                Map.of(shelf.variantId, units)));
        assertThat(held.isHeld()).isTrue();
        return quote;
    }

    private void commit(UUID quote, Shelf shelf) {
        assertThat(tx(() -> inventory.commit(shelf.tenantId, quote))).isTrue();
    }

    private record Shelf(
            UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID stockItemId, UUID orderId) {}

    private Shelf shelf(int onHand) {
        return shelf(onHand, TrackingMode.QUANTITY);
    }

    private Shelf shelf(int onHand, TrackingMode mode) {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status)
                VALUES (:id, :slug, 'Disposal test', 'Disposal test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", tenantId).param("slug", "disp-" + suffix).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Location', 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        UUID variantId = newVariant(tenantId, brandId);
        UUID stockItemId = tx(() -> inventory.listVariantAtLocation(tenantId, brandId, locationId, variantId, mode));
        if (onHand > 0) {
            tx(() -> inventory.setOnHandQuantity(
                    tenantId, locationId, variantId, BigDecimal.valueOf(onHand), "INITIAL_COUNT", "op-1"));
        }
        return new Shelf(tenantId, brandId, locationId, variantId, stockItemId, UUID.randomUUID());
    }

    private UUID newVariant(UUID tenantId, UUID brandId) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :tenantId, :brandId, :code)")
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", "SKU-" + productId.toString().substring(0, 8))
                .update();
        jdbc.sql(
                        "INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :tenantId, :brandId, :productId)")
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        return variantId;
    }

    private BigDecimal onHand(Shelf shelf) {
        return jdbc.sql("SELECT on_hand_quantity FROM inventory.positions WHERE stock_item_id = :id")
                .param("id", shelf.stockItemId)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal reserved(Shelf shelf) {
        return jdbc.sql("SELECT reserved_quantity FROM inventory.positions WHERE stock_item_id = :id")
                .param("id", shelf.stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal reservationLineQuantity(Shelf shelf, UUID quote) {
        return jdbc.sql("""
                SELECT rl.quantity FROM inventory.reservation_lines rl
                  JOIN inventory.reservations r ON r.id = rl.reservation_id
                 WHERE r.owner_id = :quote AND rl.stock_item_id = :id
                """)
                .param("quote", quote)
                .param("id", shelf.stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private List<String> movementTypes(Shelf shelf) {
        return jdbc.sql(
                        "SELECT movement_type FROM inventory.movements WHERE stock_item_id = :id ORDER BY sequence_number")
                .param("id", shelf.stockItemId)
                .query(String.class)
                .list();
    }

    private List<BigDecimal> movementDeltas(Shelf shelf, String type) {
        return jdbc.sql("""
                SELECT quantity_delta FROM inventory.movements
                 WHERE stock_item_id = :id AND movement_type = :type ORDER BY sequence_number
                """)
                .param("id", shelf.stockItemId)
                .param("type", type)
                .query(BigDecimal.class)
                .list();
    }

    private long movementCount(Shelf shelf) {
        return jdbc.sql("SELECT count(*) FROM inventory.movements WHERE stock_item_id = :id")
                .param("id", shelf.stockItemId)
                .query(Long.class)
                .single();
    }

    private BigDecimal ledgerSum(Shelf shelf) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory.movements WHERE stock_item_id = :id")
                .param("id", shelf.stockItemId)
                .query(BigDecimal.class)
                .single();
    }

    private long disposalCount(Shelf shelf) {
        return jdbc.sql("SELECT count(*) FROM inventory.stock_disposals WHERE tenant_id = :t AND order_id = :o")
                .param("t", shelf.tenantId)
                .param("o", shelf.orderId)
                .query(Long.class)
                .single();
    }

    private <T> T tx(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    /** Both pieces of work on their own threads, released together; true where one succeeded. */
    private List<Boolean> bothAtOnce(Supplier<?> left, Supplier<?> right) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Supplier<?> work : List.of(left, right)) {
                futures.add(threads.submit((Callable<Boolean>) () -> {
                    try {
                        transactions.execute(status -> {
                            try {
                                gate.await(30, TimeUnit.SECONDS);
                            } catch (Exception interrupted) {
                                throw new IllegalStateException("the gate never opened", interrupted);
                            }
                            return work.get();
                        });
                        return true;
                    } catch (RuntimeException refused) {
                        return false;
                    }
                }));
            }
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

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
