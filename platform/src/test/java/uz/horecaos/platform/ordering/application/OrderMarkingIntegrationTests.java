package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.api.MarkingConfigurationKeys;
import uz.horecaos.platform.ordering.api.OrderMarksCompleted;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.application.OrderMarkService.CaptureCommand;
import uz.horecaos.platform.ordering.application.OrderMarkService.MarkNotFoundException;
import uz.horecaos.platform.ordering.application.OrderMarkService.MarkRefusedException;
import uz.horecaos.platform.ordering.application.OrderMarkService.Refusal;
import uz.horecaos.platform.ordering.domain.MarkEntryMethod;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Marked goods at the pass, against real Postgres (ADR 0161, gap map row X.35): capture, removal, the
 * handover guard and the lock that holds them together.
 *
 * <p>Orders are written straight into the tables with foreign-key enforcement off for the fixture session
 * ({@code session_replication_role = replica}, the precedent {@code PhysicalAttributesMigrationTests} sets)
 * so a test states the order it needs rather than the catalogue, the quote and the cart behind it. What is
 * under test -- the marks table, the lock, the guard, the audit facts -- runs exactly as in production.
 *
 * <p>The concurrency tests use two real connections and real transactions, and pause one side at a named
 * point inside the guard with a latch. The end state asserted is the invariant that matters, not the order
 * of the interleaving: either a handed-over order whose live marks equal its units, or an order that did not
 * leave the pass; never a handed-over order with fewer live marks than units.
 */
class OrderMarkingIntegrationTests {

    private static final UUID TENANT = UUID.fromString("018fc300-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fc300-0000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fc300-0000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc300-0000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fc300-0000-7000-8000-0000000000c2");
    private static final UUID CHANNEL = UUID.fromString("018fc300-0000-7000-8000-0000000000d1");

    private static final UUID VODKA = UUID.fromString("018fc300-0000-7000-8000-0000000000f1");
    private static final UUID WATER = UUID.fromString("018fc300-0000-7000-8000-0000000000f2");
    private static final UUID PLOV = UUID.fromString("018fc300-0000-7000-8000-0000000000f3");

    /** Valid GS1 codes (check digits computed), serials distinct. {@code 04600000000015} is the vodka's GTIN. */
    private static final String VODKA_1 = "010460000000001521AAAAAAAA0001";

    private static final String VODKA_2 = "010460000000001521BBBBBBBB0002";
    private static final String VODKA_3 = "010460000000001521CCCCCCCC0003";

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

    private static TestDatabase.Handle db;
    private static HikariDataSource pool;

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcOrderStore orders;
    private ObservableMarkStore marks;
    private FakeFiscal fiscal;
    private RecordingAudit audit;
    private final List<Object> published = new CopyOnWriteArrayList<>();
    private FieldProtection protection;
    private MarkingMetrics metrics;
    private OrderMarkService service;
    private OrderStateService state;
    private Map<String, Object> settings;
    private FakeConfigurationResolver configuration;
    private AlwaysAllowed limiter;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        db = TestDatabase.migrated();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(db.username());
        config.setPassword(db.password());
        config.setMaximumPoolSize(6);
        // The fixture inserts orders and lines without their parents (quote, cart, publication, catalogue):
        // the marks table is what is under test, and it has its own foreign keys, which this does not touch
        // because the lines they reference are real rows.
        config.setConnectionInitSql("SET session_replication_role = replica");
        pool = new HikariDataSource(config);
    }

    @AfterAll
    static void stopDatabase() {
        if (pool != null) {
            pool.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(pool);
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        jdbc.sql("TRUNCATE TABLE ordering.order_line_marks, ordering.order_outcomes, ordering.order_state_history, "
                        + "ordering.order_lines, ordering.order_revisions, ordering.orders CASCADE")
                .update();

        orders = new JdbcOrderStore(jdbc);
        marks = new ObservableMarkStore(jdbc);
        fiscal = new FakeFiscal();
        audit = new RecordingAudit();
        protection = TestProtection.envelope();
        metrics = MarkingMetrics.none();
        limiter = new AlwaysAllowed();
        settings = new HashMap<>();
        settings.put(FeatureFlagKeys.MARKED_GOODS.code(), true);
        configuration = new FakeConfigurationResolver(settings);
        rebuildServices();

        // The vodka is a marked good whose barcode is the EAN-13 behind GTIN 04600000000015; water is not marked.
        fiscal.mark(VODKA, "4600000000015");
        published.clear();
    }

    private void rebuildServices() {
        configuration = new FakeConfigurationResolver(settings);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new OrderMarkService(
                marks, orders, fiscal, protection, configuration, audit, published::add, limiter, metrics, clock);
        state = new OrderStateService(
                orders,
                mock(LocationCapacityPort.class),
                mock(OrderInventoryProcess.class),
                mock(OrderPaymentProcess.class),
                mock(OrderAcceptancePolicyService.class),
                mock(uz.horecaos.platform.ordering.api.OrderSettlementPort.class),
                audit,
                published::add,
                clock,
                mock(PromoCodeRedemptionPort.class),
                service);
    }

    // ----------------------------------------------------------------- capture

    @Test
    @DisplayName("a unit is captured, the count moves, and the last unit completes the order exactly once")
    void captureCountsAndCompletes() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 3, "Vodka"), line(WATER, 1, "Water"));

        var first = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.gtin()).isEqualTo("04600000000015");
        assertThat(first.captured()).isEqualTo(1);
        assertThat(first.required()).isEqualTo(3);
        assertThat(first.orderMarksComplete()).isFalse();
        assertThat(first.gtinChecked())
                .as("the classification states a barcode and it matched")
                .isTrue();

        var second = capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(published)
                .as("nothing is published before the order is complete")
                .isEmpty();

        var third = capture(order, 0, VODKA_3, MarkEntryMethod.CAMERA);
        assertThat(third.sequence()).isEqualTo(3);
        assertThat(third.orderMarksComplete()).isTrue();

        assertThat(published)
                .singleElement()
                .isInstanceOf(OrderMarksCompleted.class)
                .satisfies(event -> {
                    OrderMarksCompleted completed = (OrderMarksCompleted) event;
                    assertThat(completed.markedLineCount()).isEqualTo(1);
                    assertThat(completed.markedUnitCount()).isEqualTo(3);
                    assertThat(completed.orderId()).isEqualTo(order.id());
                });

        List<Map<String, Object>> rows =
                jdbc.sql("""
                        SELECT sequence, capture_stage, entry_method, gtin, gtin_checked, released_at
                        FROM ordering.order_line_marks WHERE order_id = :o ORDER BY sequence
                        """).param("o", order.id()).query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("capture_stage")).isEqualTo("PICK"));
        assertThat(rows.get(2).get("entry_method")).isEqualTo("CAMERA");
        assertThat(metricCount("accepted", "PICK")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("the stage is derived from the status: PICK while confirmed or preparing, HANDOVER once ready")
    void stageFollowsTheStatus() {
        Order preparing = order(OrderStatus.PREPARING, line(VODKA, 1, "Vodka"));
        Order ready = order(OrderStatus.READY, line(VODKA, 1, "Vodka"));

        capture(preparing, 0, VODKA_1, MarkEntryMethod.SCANNER);
        capture(ready, 0, VODKA_2, MarkEntryMethod.SCANNER);

        assertThat(stageOf(preparing)).isEqualTo("PICK");
        assertThat(stageOf(ready)).isEqualTo("HANDOVER");
    }

    @Test
    @DisplayName("an order that has left the pass, or never reached it, cannot have a code captured against it")
    void refusingStatuses() {
        for (OrderStatus status : List.of(
                OrderStatus.RECEIVED,
                OrderStatus.AWAITING_APPROVAL,
                OrderStatus.FULFILLING,
                OrderStatus.COMPLETED,
                OrderStatus.CANCELLED)) {
            Order order = order(status, line(VODKA, 1, "Vodka"));
            assertRefused(Refusal.ORDER_NOT_MARKABLE, () -> capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER));
        }
        assertThat(liveMarks()).isZero();
    }

    @Test
    @DisplayName(
            "a repeated unit is refused on its own line and on any other order of the tenant, never across tenants")
    void duplicates() {
        Order first = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"));
        Order second = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"));
        capture(first, 0, VODKA_1, MarkEntryMethod.SCANNER);

        var sameLine = refusalOf(() -> capture(first, 0, VODKA_1, MarkEntryMethod.SCANNER));
        assertThat(sameLine.refusal()).isEqualTo(Refusal.MARKING_CODE_ALREADY_CAPTURED);
        assertThat(sameLine.properties()).containsEntry("onThisLine", true);

        var otherOrder = refusalOf(() -> capture(second, 0, VODKA_1, MarkEntryMethod.SCANNER));
        assertThat(otherOrder.refusal()).isEqualTo(Refusal.MARKING_CODE_ALREADY_CAPTURED);
        assertThat(otherOrder.properties()).containsEntry("onThisLine", false);

        // The same text in another tenant is another unit: no collision, no leak that the first exists.
        Order foreign = orderOf(OTHER_TENANT, OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"));
        fiscalForOtherTenant();
        var accepted = service.capture(new CaptureCommand(
                OTHER_TENANT,
                BRAND,
                LOCATION,
                foreign.id(),
                foreign.lines().get(0).id(),
                VODKA_1,
                MarkEntryMethod.SCANNER,
                "op"));
        assertThat(accepted.captured()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the GTIN must be the product's; a classification with no barcode is recorded as unchecked, not refused")
    void productMatch() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"));
        var wrong = refusalOf(() -> capture(order, 0, "011478200010305121XXXXXXXX0009", MarkEntryMethod.SCANNER));
        assertThat(wrong.refusal())
                .as("a valid GS1 code of another product")
                .isEqualTo(Refusal.MARKING_CODE_WRONG_PRODUCT);

        fiscal.mark(VODKA, null);
        var unchecked = capture(order, 0, "011478200010305121XXXXXXXX0009", MarkEntryMethod.SCANNER);
        assertThat(unchecked.gtinChecked()).isFalse();
    }

    @Test
    @DisplayName(
            "an unmarked line, a complete line, an unknown line and another branch's order are refused or not found")
    void lineAndOrderChecks() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"), line(WATER, 1, "Water"));
        assertRefused(Refusal.LINE_NOT_MARKED, () -> capture(order, 1, VODKA_1, MarkEntryMethod.SCANNER));

        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        assertRefused(Refusal.LINE_MARKS_COMPLETE, () -> capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER));

        assertThatThrownBy(() -> service.capture(new CaptureCommand(
                        TENANT,
                        BRAND,
                        LOCATION,
                        order.id(),
                        UUID.randomUUID(),
                        VODKA_3,
                        MarkEntryMethod.SCANNER,
                        "op")))
                .isInstanceOf(MarkNotFoundException.class);
        assertThatThrownBy(() -> service.capture(new CaptureCommand(
                        TENANT,
                        BRAND,
                        OTHER_LOCATION,
                        order.id(),
                        order.lines().get(0).id(),
                        VODKA_3,
                        MarkEntryMethod.SCANNER,
                        "op")))
                .as("the path names a branch the order is not at")
                .isInstanceOf(MarkNotFoundException.class);
    }

    @Test
    @DisplayName("a malformed scan is refused without echoing it; a keyed scan is refused unless the policy allows it")
    void malformedAndKeyed() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"));
        var malformed = refusalOf(() -> capture(order, 0, "not-a-code", MarkEntryMethod.SCANNER));
        assertThat(malformed.refusal()).isEqualTo(Refusal.MARKING_CODE_MALFORMED);
        assertThat(malformed.getMessage()).doesNotContain("not-a-code");

        var notGs1 =
                refusalOf(() -> capture(order, 0, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", MarkEntryMethod.SCANNER));
        assertThat(notGs1.refusal()).isEqualTo(Refusal.MARKING_CODE_MALFORMED);

        var keyed = refusalOf(() -> capture(order, 0, VODKA_1, MarkEntryMethod.KEYED));
        assertThat(keyed.refusal()).isEqualTo(Refusal.MARKING_KEYED_ENTRY_REFUSED);

        settings.put(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY.code(), true);
        rebuildServices();
        assertThat(capture(order, 0, VODKA_1, MarkEntryMethod.KEYED).captured()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "with the structure check off, only the character set and length are checked and the GTIN is unchecked")
    void structureCheckNone() {
        settings.put(MarkingConfigurationKeys.STRUCTURE_CHECK.code(), "NONE");
        rebuildServices();
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"));

        var accepted = capture(order, 0, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", MarkEntryMethod.SCANNER);

        assertThat(accepted.gtin()).isEqualTo("00000000000000");
        assertThat(accepted.gtinChecked())
                .as("no GTIN could be read, so none could be compared")
                .isFalse();
    }

    @Test
    @DisplayName(
            "with the flag off every capture endpoint answers FEATURE_DISABLED, the guard is skipped and an order read carries nothing")
    void flagOff() {
        settings.put(FeatureFlagKeys.MARKED_GOODS.code(), false);
        rebuildServices();
        Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));

        assertRefused(Refusal.FEATURE_DISABLED, () -> capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER));
        assertRefused(Refusal.FEATURE_DISABLED, () -> service.read(TENANT, LOCATION, order.id()));
        assertRefused(
                Refusal.FEATURE_DISABLED,
                () -> service.remove(
                        TENANT, LOCATION, order.id(), order.lines().get(0).id(), UUID.randomUUID(), "MISSCAN", "op"));
        assertThat(service.markingOf(TENANT, BRAND, order.id())).isEmpty();
        assertThat(service.markCounts(TENANT, order.id())).isEmpty();

        // The guard is skipped: a marked order with no scans leaves the pass, which is exactly today's behaviour.
        var result = tx.execute(status ->
                state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null));
        assertThat(result).isNotNull();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("scans beyond 240 a minute per principal are refused with the seconds to wait")
    void rateLimit() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"));
        limiter.denyFor(Duration.ofSeconds(17));

        var refused = refusalOf(() -> capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER));

        assertThat(refused.refusal()).isEqualTo(Refusal.RATE_LIMITED);
        assertThat(refused.properties()).containsEntry("retryAfterSeconds", 17L);
        assertThat(limiter.lastKey().operation()).isEqualTo(OrderMarkService.CAPTURE_OPERATION);
        assertThat(liveMarks()).isZero();
    }

    // ----------------------------------------------------------------- removal

    @Test
    @DisplayName("a scan is taken back with a coded reason, the unit can be scanned again, and nothing is deleted")
    void removalReleasesAndTheUnitCanBeRescanned() {
        Order order = order(OrderStatus.PREPARING, line(VODKA, 2, "Vodka"));
        var first = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        var removal = inTx(() -> service.remove(
                TENANT, LOCATION, order.id(), order.lines().get(0).id(), first.markId(), "MISSCAN", "op-1"));

        assertThat(removal.captured()).isZero();
        assertThat(removal.required()).isEqualTo(2);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM ordering.order_line_marks WHERE id = :id AND released_reason = 'MISSCAN'")
                        .param("id", first.markId())
                        .query(Integer.class)
                        .single())
                .as("released, with its reason, and still there")
                .isEqualTo(1);
        var again = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        assertThat(again.sequence()).as("the lowest free sequence is reused").isEqualTo(1);
        assertThat(audit.codes()).contains("ordering.order.line_mark_removed", "ordering.order.line_mark_captured");
    }

    @Test
    @DisplayName(
            "a scan cannot be taken back once the order has left the pass, nor with a free-text or platform reason")
    void removalRefusals() {
        Order order = order(OrderStatus.READY, line(VODKA, 1, "Vodka"));
        var captured = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        assertThatThrownBy(() -> service.remove(
                        TENANT,
                        LOCATION,
                        order.id(),
                        order.lines().get(0).id(),
                        captured.markId(),
                        "ORDER_CANCELLED",
                        "op"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.remove(
                        TENANT,
                        LOCATION,
                        order.id(),
                        order.lines().get(0).id(),
                        captured.markId(),
                        "Aziza said so",
                        "op"))
                .isInstanceOf(IllegalArgumentException.class);

        setStatus(order, OrderStatus.FULFILLING);
        assertRefused(
                Refusal.ORDER_NOT_MARKABLE,
                () -> service.remove(
                        TENANT, LOCATION, order.id(), order.lines().get(0).id(), captured.markId(), "MISSCAN", "op"));
        assertThat(liveMarks()).isEqualTo(1);
    }

    // ----------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another tenant's order, line and mark ids answer not-found on capture, removal and read")
    void tenantIsolation() {
        Order mine = order(OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"));
        var captured = capture(mine, 0, VODKA_1, MarkEntryMethod.SCANNER);

        assertThatThrownBy(() -> service.capture(new CaptureCommand(
                        OTHER_TENANT,
                        BRAND,
                        LOCATION,
                        mine.id(),
                        mine.lines().get(0).id(),
                        VODKA_2,
                        MarkEntryMethod.SCANNER,
                        "op")))
                .isInstanceOf(MarkNotFoundException.class);
        assertThatThrownBy(() -> service.remove(
                        OTHER_TENANT,
                        LOCATION,
                        mine.id(),
                        mine.lines().get(0).id(),
                        captured.markId(),
                        "MISSCAN",
                        "op"))
                .isInstanceOf(MarkNotFoundException.class);
        assertThatThrownBy(() -> service.read(OTHER_TENANT, LOCATION, mine.id()))
                .isInstanceOf(MarkNotFoundException.class);
        assertThat(liveMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName("the guard counts only its own tenant's marks, however many another tenant holds")
    void theGuardCountsOnlyItsOwnTenant() {
        Order mine = order(OrderStatus.READY, line(VODKA, 1, "Vodka"));
        Order theirs = orderOf(OTHER_TENANT, OrderStatus.READY, line(VODKA, 1, "Vodka"));
        fiscalForOtherTenant();
        service.capture(new CaptureCommand(
                OTHER_TENANT,
                BRAND,
                LOCATION,
                theirs.id(),
                theirs.lines().get(0).id(),
                VODKA_1,
                MarkEntryMethod.SCANNER,
                "op"));

        assertThat(service.incompleteLines(TENANT, BRAND, mine.id()))
                .containsExactly(mine.lines().get(0).id());
        assertThat(service.incompleteLines(OTHER_TENANT, BRAND, theirs.id())).isEmpty();
    }

    // ----------------------------------------------------------------- the guard

    @Test
    @DisplayName(
            "an order cannot leave the pass while a marked line is short, naming the line; it can once it is complete")
    void guardOnAdvance() {
        Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"), line(WATER, 1, "Water"));
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        assertThatThrownBy(() -> tx.execute(status ->
                        state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null)))
                .isInstanceOfSatisfying(
                        OrderStateService.MarksIncompleteException.class,
                        refused -> assertThat(refused.lineIds())
                                .containsExactly(order.lines().get(0).id()));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.READY);

        capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER);
        tx.execute(status ->
                state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("an order with no marked line is never held and never locked")
    void anOrdinaryOrderIsNotHeld() {
        Order order = order(OrderStatus.READY, line(WATER, 2, "Water"));
        marks.lockCalls.set(0);

        tx.execute(status ->
                state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null));

        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(marks.lockCalls.get())
                .as("no lock, no count: an ordinary order pays one catalog read")
                .isZero();
    }

    @Test
    @DisplayName("a kitchen proposal to hand over is refused while a marked line is short, like an impossible edge")
    void guardOnKitchenProposal() {
        Order order = order(OrderStatus.READY, line(VODKA, 1, "Vodka"));

        var refused = tx.execute(status -> state.proposeProgress(
                TENANT, order.id(), OrderStatus.COMPLETED, "key-1", "KITCHEN_DONE", "SERVICE", "kds", null));

        assertThat(refused).isEqualTo(OrderStateService.ProgressProposal.REFUSED);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.READY);
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        var applied = tx.execute(status -> state.proposeProgress(
                TENANT, order.id(), OrderStatus.COMPLETED, "key-2", "KITCHEN_DONE", "SERVICE", "kds", null));
        assertThat(applied).isEqualTo(OrderStateService.ProgressProposal.APPLIED);
    }

    @Test
    @DisplayName("a reclassification mid-order cannot drop a line that already holds a scan")
    void aLineThatHoldsAMarkStaysMarked() {
        Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        fiscal.unmark(VODKA); // the catalog now says it is not a marked good

        assertThat(service.incompleteLines(TENANT, BRAND, order.id()))
                .containsExactly(order.lines().get(0).id());
    }

    @Test
    @DisplayName(
            "a reclassification that makes a line marked holds the open order, which is the legally correct direction")
    void aLaterMarkingHoldsAnOpenOrder() {
        Order order = order(OrderStatus.READY, line(WATER, 1, "Water"));
        assertThat(service.incompleteLines(TENANT, BRAND, order.id())).isEmpty();

        fiscal.mark(WATER, null);

        assertThat(service.incompleteLines(TENANT, BRAND, order.id()))
                .containsExactly(order.lines().get(0).id());
    }

    @Test
    @DisplayName(
            "cancelling an order at the pass frees its marks in the same transaction; they can then be scanned again")
    void cancellationReleases() {
        Order order = order(OrderStatus.PREPARING, line(VODKA, 1, "Vodka"));
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        tx.execute(status ->
                state.cancel(TENANT, order.id(), 1, "CUSTOMER_CHANGED_MIND", "USER", "op", null, cancelOutcome()));

        assertThat(liveMarks()).isZero();
        assertThat(jdbc.sql("SELECT released_reason FROM ordering.order_line_marks WHERE order_id = :o")
                        .param("o", order.id())
                        .query(String.class)
                        .single())
                .isEqualTo("ORDER_CANCELLED");
        assertThat(audit.codes()).contains("ordering.order.marks_released");

        Order next = order(OrderStatus.CONFIRMED, line(VODKA, 1, "Vodka"));
        assertThat(capture(next, 0, VODKA_1, MarkEntryMethod.SCANNER).captured())
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- the lock

    @Test
    @DisplayName(
            "a removal waits for a hand-over that holds the order, then finds it gone: never a handed-over order short of units")
    void removalWaitsForTheGuardThatHoldsTheLock() throws Exception {
        Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));
        var a = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER);

        CountDownLatch holdingTheLock = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        marks.afterLock = () -> {
            holdingTheLock.countDown();
            await(letGo);
        };

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> advance = threads.submit(() -> tx.execute(status ->
                    state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null)));
            assertThat(holdingTheLock.await(10, TimeUnit.SECONDS)).isTrue();
            marks.afterLock = () -> {};

            Future<?> removal = threads.submit(() -> tx.execute(status -> service.remove(
                    TENANT, LOCATION, order.id(), order.lines().get(0).id(), a.markId(), "MISSCAN", "op-2")));
            assertThatThrownBy(() -> removal.get(500, TimeUnit.MILLISECONDS))
                    .as("the removal blocks behind the order lock the guard holds")
                    .isInstanceOf(TimeoutException.class);

            letGo.countDown();
            advance.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> removal.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MarkRefusedException.class)
                    .cause()
                    .satisfies(cause ->
                            assertThat(((MarkRefusedException) cause).refusal()).isEqualTo(Refusal.ORDER_NOT_MARKABLE));
        } finally {
            letGo.countDown();
            threads.shutdownNow();
        }

        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(liveMarks())
                .as("the handed-over order still holds every unit")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "a removal that commits first leaves the guard counting one fewer, so the order does not leave the pass")
    void aCommittedRemovalMakesTheGuardRefuse() {
        Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));
        var a = capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);
        capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER);

        tx.execute(status -> service.remove(
                TENANT, LOCATION, order.id(), order.lines().get(0).id(), a.markId(), "UNIT_DAMAGED", "op"));

        assertThatThrownBy(() -> tx.execute(status ->
                        state.advance(TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null)))
                .isInstanceOf(OrderStateService.MarksIncompleteException.class);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.READY);
        assertThat(liveMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a removal that arrives while the guard is paused before the lock or between its count and the status change leaves a consistent end state")
    void everyInterleavingEndsConsistent() throws Exception {
        for (String pausePoint : List.of("before-lock", "after-count")) {
            Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));
            var a = capture(order, 0, uniqueCode("A" + pausePoint), MarkEntryMethod.SCANNER);
            capture(order, 0, uniqueCode("B" + pausePoint), MarkEntryMethod.SCANNER);

            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            Runnable pause = () -> {
                paused.countDown();
                await(resume);
            };
            if (pausePoint.equals("before-lock")) {
                marks.beforeLock = pause;
            } else {
                marks.afterCount = pause;
            }

            ExecutorService threads = Executors.newFixedThreadPool(2);
            Future<Object> advance = null;
            Future<Object> removal = null;
            try {
                advance = threads.submit(() -> tx.execute(status -> state.advance(
                        TENANT, order.id(), OrderStatus.COMPLETED, 1, "HANDED_OVER", "USER", "op", null)));
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                marks.beforeLock = () -> {};
                marks.afterCount = () -> {};
                removal = threads.submit(() -> tx.execute(status -> service.remove(
                        TENANT, LOCATION, order.id(), order.lines().get(0).id(), a.markId(), "MISSCAN", "op-2")));
                // Whoever gets the lock first wins; either way the other is told, and the end state is consistent.
                Thread.sleep(300);
                resume.countDown();
            } finally {
                resume.countDown();
            }
            Throwable advanceFailure = failureOf(advance);
            Throwable removalFailure = failureOf(removal);
            threads.shutdownNow();

            OrderStatus end = statusOf(order);
            int live = liveMarks(order);
            if (end == OrderStatus.COMPLETED) {
                assertThat(live)
                        .as("%s: handed over, so every unit is still held", pausePoint)
                        .isEqualTo(2);
                assertThat(removalFailure).as(pausePoint).isInstanceOf(MarkRefusedException.class);
            } else {
                assertThat(end).as(pausePoint).isEqualTo(OrderStatus.READY);
                assertThat(advanceFailure)
                        .as("%s: the order did not leave the pass because it was short", pausePoint)
                        .isInstanceOf(OrderStateService.MarksIncompleteException.class);
                assertThat(live).as(pausePoint).isEqualTo(1);
            }
        }
    }

    // ----------------------------------------------------------------- the port and the order read

    @Test
    @DisplayName(
            "the port gives counts without any code, and a receipt source with the protected envelopes in sequence order")
    void thePort() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"), line(WATER, 1, "Water"));
        capture(order, 0, VODKA_2, MarkEntryMethod.SCANNER);
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        assertThat(service.markCounts(TENANT, order.id()))
                .containsExactly(
                        new OrderMarksPort.LineMarkCount(order.lines().get(0).id(), 2, 2));
        assertThat(service.markingOf(TENANT, BRAND, order.id()))
                .containsOnlyKeys(order.lines().get(0).id());

        OrderMarksPort.ReceiptSource source = service.receiptSource(TENANT, order.id());
        assertThat(source.hasMarkedLine()).isTrue();
        OrderMarksPort.ReceiptLine vodka = source.lines().get(0);
        assertThat(vodka.marked()).isTrue();
        assertThat(vodka.requiredMarks()).isEqualTo(2);
        assertThat(vodka.marks()).extracting(OrderMarksPort.MarkRef::sequence).containsExactly(1, 2);
        // The envelope decrypts to the canonical code under the row it is bound to, and under no other.
        var first = vodka.marks().get(0);
        String revealed = protection.reveal(
                TENANT,
                uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(first.protectedCode()),
                new FieldProtection.RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, first.markId()),
                "test");
        assertThat(revealed).isEqualTo(VODKA_2);
        assertThatThrownBy(() -> protection.reveal(
                        TENANT,
                        uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(first.protectedCode()),
                        new FieldProtection.RecordRef(
                                OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, UUID.randomUUID()),
                        "test"))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
        assertThat(source.lines().get(1).marked()).isFalse();
    }

    @Test
    @DisplayName("the read lists the scanned units with their product identifier and never a code")
    void theReadNeverReturnsACode() {
        Order order = order(OrderStatus.CONFIRMED, line(VODKA, 2, "Vodka"));
        capture(order, 0, VODKA_1, MarkEntryMethod.SCANNER);

        var progress = service.read(TENANT, LOCATION, order.id());

        assertThat(progress).singleElement().satisfies(line -> {
            assertThat(line.required()).isEqualTo(2);
            assertThat(line.captured()).isEqualTo(1);
            assertThat(line.marks())
                    .singleElement()
                    .satisfies(mark -> assertThat(mark.gtin()).isEqualTo("04600000000015"));
            assertThat(line.toString()).doesNotContain("AAAAAAAA0001").doesNotContain(VODKA_1);
        });
    }

    // ----------------------------------------------------------------- privacy

    @Test
    @DisplayName(
            "a scanned code reaches no log line, audit change document, event payload, metric label or refusal message")
    void aCodeNeverLeavesTheProtectedRow() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        root.setLevel(Level.ALL);
        root.addAppender(appender);
        try {
            Order order = order(OrderStatus.READY, line(VODKA, 2, "Vodka"));
            String serial = "SECRETSERIAL4242";
            String secret = "010460000000001521" + serial;
            var captured = capture(order, 0, secret, MarkEntryMethod.SCANNER);
            // Every way to be refused, with the secret in the request each time.
            refusalOf(() -> capture(order, 0, secret, MarkEntryMethod.SCANNER));
            refusalOf(() -> capture(order, 0, secret, MarkEntryMethod.KEYED));
            refusalOf(() -> capture(order, 0, "011478200010305121" + serial, MarkEntryMethod.SCANNER));
            capture(order, 0, "010460000000001521OTHERSERIAL7", MarkEntryMethod.SCANNER);
            tx.execute(status -> service.remove(
                    TENANT, LOCATION, order.id(), order.lines().get(0).id(), captured.markId(), "MISSCAN", "op"));
            tx.execute(status ->
                    state.cancel(TENANT, order.id(), 1, "CUSTOMER_CHANGED_MIND", "USER", "op", null, cancelOutcome()));

            List<String> everything = new ArrayList<>();
            appender.list.forEach(event -> {
                everything.add(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) {
                    everything.add(event.getThrowableProxy().getMessage());
                }
            });
            audit.facts.forEach(fact ->
                    everything.add(fact.changeDocument().toString() + fact.reason() + fact.evidenceReference()));
            published.forEach(event -> everything.add(event.toString()
                    + (event instanceof uz.horecaos.platform.ordering.api.OrderingEvent ordering
                            ? String.valueOf(ordering.payload())
                            : "")));
            metrics.registry().getMeters().stream()
                    .map(Meter::getId)
                    .flatMap(id -> id.getTags().stream())
                    .map(Tag::getValue)
                    .forEach(everything::add);
            everything.add(captured.toString());

            assertThat(everything).isNotEmpty();
            assertThat(everything.stream().filter(text -> text != null && text.contains(serial)))
                    .as("no sink holds the serial of a scanned code")
                    .isEmpty();
            assertThat(everything.stream().filter(text -> text != null && text.contains("OTHERSERIAL7")))
                    .isEmpty();
            // The secret is in the table, once, as an envelope: the one place it is allowed to be.
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM ordering.order_line_marks WHERE code_encrypted LIKE '%SECRETSERIAL%'")
                            .query(Integer.class)
                            .single())
                    .as("never stored in clear")
                    .isZero();
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
    }

    // ----------------------------------------------------------------- helpers

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return java.util.Objects.requireNonNull(tx.execute(status -> work.get()));
    }

    private OrderMarkService.CaptureResult capture(Order order, int lineIndex, String code, MarkEntryMethod method) {
        return inTx(() -> service.capture(new CaptureCommand(
                order.tenant(),
                BRAND,
                LOCATION,
                order.id(),
                order.lines().get(lineIndex).id(),
                code,
                method,
                "operator-1")));
    }

    private static String uniqueCode(String serial) {
        String padded = (serial + "XXXXXXXXXXXXXXXXXXXX").substring(0, 14).replaceAll("[^A-Za-z0-9]", "Z");
        return "010460000000001521" + padded;
    }

    private static MarkRefusedException refusalOf(Runnable action) {
        try {
            action.run();
        } catch (MarkRefusedException refused) {
            return refused;
        }
        throw new AssertionError("expected a refusal");
    }

    private static void assertRefused(Refusal expected, Runnable action) {
        assertThat(refusalOf(action).refusal()).isEqualTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static @Nullable Throwable failureOf(@Nullable Future<Object> future) throws Exception {
        if (future == null) {
            return null;
        }
        try {
            future.get(15, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private double metricCount(String outcome, String stage) {
        var counter = metrics.registry()
                .find(MarkingMetrics.CAPTURE)
                .tag("outcome", outcome)
                .tag("stage", stage)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long liveMarks() {
        return jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE released_at IS NULL")
                .query(Long.class)
                .single();
    }

    private int liveMarks(Order order) {
        return jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE order_id = :o AND released_at IS NULL")
                .param("o", order.id())
                .query(Integer.class)
                .single();
    }

    private String stageOf(Order order) {
        return jdbc.sql("SELECT capture_stage FROM ordering.order_line_marks WHERE order_id = :o")
                .param("o", order.id())
                .query(String.class)
                .single();
    }

    private OrderStatus statusOf(Order order) {
        return orders.find(order.tenant(), order.id()).orElseThrow().status();
    }

    private void setStatus(Order order, OrderStatus status) {
        jdbc.sql("UPDATE ordering.orders SET status = :s WHERE tenant_id = :t AND id = :id")
                .param("s", status.name())
                .param("t", order.tenant())
                .param("id", order.id())
                .update();
    }

    private void fiscalForOtherTenant() {
        // The classification fake is tenant-blind; the other tenant sells the same marked vodka.
        fiscal.mark(VODKA, "4600000000015");
    }

    private static uz.horecaos.platform.ordering.domain.OrderOutcome cancelOutcome() {
        return new uz.horecaos.platform.ordering.domain.OrderOutcome(
                uz.horecaos.platform.ordering.domain.TerminalOutcomeKind.CANCELLED,
                uz.horecaos.platform.ordering.domain.OutcomeSystemCategory.OTHER,
                null,
                null,
                null,
                uz.horecaos.platform.ordering.domain.StockDisposition.RELEASE,
                uz.horecaos.platform.ordering.domain.LiabilityParty.TENANT,
                uz.horecaos.platform.ordering.domain.CustomerRefund.FULL,
                false,
                null);
    }

    // ----------------------------------------------------------------- fixture

    private record Line(UUID id, UUID variantId, int quantity, String name) {}

    private record Order(UUID tenant, UUID id, List<Line> lines) {}

    private static Line line(UUID variant, int quantity, String name) {
        return new Line(UUID.randomUUID(), variant, quantity, name);
    }

    private Order order(OrderStatus status, Line... lines) {
        return orderOf(TENANT, status, lines);
    }

    private Order orderOf(UUID tenantId, OrderStatus status, Line... lines) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    promise_basis, version, confirmed_at)
                VALUES (:id, :number, :t, :b, :l, :ch, 'STOREFRONT', :guest, 'PICKUP', 'AUTO_CONFIRM', NULL, 0,
                    'NONE', NULL, :status, 'UZS', 100000, 0, 100000, :quote, 'hash', :pub, :cart, :guest,
                    'NOT_PROMISED', 1, now())
                """)
                .param("id", orderId)
                .param("number", "M-" + orderId.toString().substring(0, 6))
                .param("t", tenantId)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("ch", CHANNEL)
                .param("guest", "guest-" + orderId)
                .param("status", status.name())
                .param("quote", UUID.randomUUID())
                .param("pub", UUID.randomUUID())
                .param("cart", UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor, total_minor)
                VALUES (:o, 1, :t, 'CHECKOUT', :quote, 'hash', 'UZS', 100000, 0, 100000)
                """)
                .param("o", orderId)
                .param("t", tenantId)
                .param("quote", UUID.randomUUID())
                .update();
        int number = 1;
        for (Line line : lines) {
            jdbc.sql("""
                    INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_product_id,
                        source_variant_id, product_name_snapshot, quantity, unit_amount_minor, base_amount_minor,
                        final_amount_minor, tax_amount_minor)
                    VALUES (:id, :t, :o, :n, :product, :variant, :name, :q, 50000, 50000, :total, 0)
                    """)
                    .param("id", line.id())
                    .param("t", tenantId)
                    .param("o", orderId)
                    .param("n", number++)
                    .param("product", UUID.randomUUID())
                    .param("variant", line.variantId())
                    .param("name", line.name())
                    .param("q", BigDecimal.valueOf(line.quantity()))
                    .param("total", 50000L * line.quantity())
                    .update();
        }
        return new Order(tenantId, orderId, List.of(lines));
    }

    /** The mark store with hooks at the points inside the guard where the lock tests pause one side. */
    private static final class ObservableMarkStore extends JdbcOrderMarkStore {

        volatile Runnable beforeLock = () -> {};
        volatile Runnable afterLock = () -> {};
        volatile Runnable afterCount = () -> {};
        final java.util.concurrent.atomic.AtomicInteger lockCalls = new java.util.concurrent.atomic.AtomicInteger();

        ObservableMarkStore(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public boolean lockOrder(UUID tenantId, UUID orderId) {
            lockCalls.incrementAndGet();
            beforeLock.run();
            boolean locked = super.lockOrder(tenantId, orderId);
            afterLock.run();
            return locked;
        }

        @Override
        public Map<UUID, Integer> liveCounts(UUID tenantId, UUID orderId) {
            Map<UUID, Integer> counts = super.liveCounts(tenantId, orderId);
            afterCount.run();
            return counts;
        }
    }

    private static final class FakeFiscal implements FiscalNodeFacts {

        private final Map<UUID, FiscalNodeFacts.MarkedNode> marked = new HashMap<>();

        void mark(UUID variant, @Nullable String barcode) {
            marked.put(variant, new FiscalNodeFacts.MarkedNode(barcode));
        }

        void unmark(UUID variant) {
            marked.remove(variant);
        }

        @Override
        public Set<UUID> markedNodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
            return priceableIds.stream().filter(marked::containsKey).collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Map<UUID, FiscalNodeFacts.MarkedNode> markedNodeDetails(
                UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
            Map<UUID, FiscalNodeFacts.MarkedNode> result = new HashMap<>();
            priceableIds.stream().filter(marked::containsKey).forEach(id -> result.put(id, marked.get(id)));
            return result;
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        List<String> codes() {
            return facts.stream().map(AuditFact::actionCode).toList();
        }
    }

    private static final class AlwaysAllowed implements RateLimiter {

        private volatile @Nullable Duration denyFor;
        private volatile RateLimiter.@Nullable Key lastKey;

        void denyFor(Duration retryAfter) {
            this.denyFor = retryAfter;
        }

        RateLimiter.Key lastKey() {
            return java.util.Objects.requireNonNull(lastKey, "the limiter was never asked");
        }

        @Override
        public Decision check(Key key, Policy policy) {
            lastKey = key;
            Duration deny = denyFor;
            return deny == null ? Decision.allowed(policy.permits()) : Decision.denied(deny);
        }
    }
}
