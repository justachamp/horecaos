package uz.horecaos.platform.dinein;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.dinein.application.ClaimLifecycleService;
import uz.horecaos.platform.dinein.application.ClaimMetrics;
import uz.horecaos.platform.dinein.application.DineInErasureParticipant;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.FloorPlanService.WalkInChange;
import uz.horecaos.platform.dinein.application.QrEntryService;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestAdmission;
import uz.horecaos.platform.dinein.application.ReservationService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.application.WalkInSeatingService;
import uz.horecaos.platform.dinein.application.WalkInSeatingService.Seating;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.domain.RoundStatuses;
import uz.horecaos.platform.dinein.domain.SessionOrigin;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.ordering.JdbcSessionOrderSource;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SectionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * A guest seats themselves at a free table (ADR 0143), against a real PostgreSQL.
 *
 * <p>Wired by hand, in the way {@code DineInTests} wires the rest of the module, and
 * for the same reason: the properties that matter here belong to the database. Whether
 * one account firing claims at six different tables ends with one claim is a question
 * about a partial unique index. Whether M accounts against a branch cap of K end with
 * exactly K is a question about a row lock and a count taken under it. Neither can be
 * asked of a mock, and neither is asked of one: every race below is real threads
 * released by one latch against the real services.
 *
 * <p>The clock is a clock the test moves, not an instant it asserts at. A claim's
 * window, the payment deferral bound and the daily cap are all durations, and a test
 * that fixes the clock and asserts the outcome has asserted an instant.
 */
class WalkInSeatingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    /** A Friday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-28T07:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcDineInStore store;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private FloorPlanService floorPlan;
    private ReservationService reservations;
    private TableSessionService sessions;
    private QrEntryService qr;
    private WalkInSeatingService walkIn;
    private ClaimLifecycleService lifecycle;
    private ClaimMetrics metrics;
    private RecordingAuditRecorder audit;
    private final Set<UUID> blacklisted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> printed = new HashMap<>();

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID section;
    private UUID tableOne;
    private UUID tableTwo;

    private UUID otherBranch;
    private UUID otherTable;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for walk-in seating tests");
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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE dinein.session_orders, dinein.session_tables, "
                        + "dinein.table_sessions, dinein.reservation_tables, dinein.reservations, "
                        + "dinein.qr_guest_sessions, dinein.tables, dinein.sections, "
                        + "dinein.location_settings CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOON);
        blacklisted.clear();
        printed.clear();
        store = new JdbcDineInStore(jdbc);
        audit = new RecordingAuditRecorder();
        metrics = ClaimMetrics.none();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        floorPlan = new FloorPlanService(store, audit, clock);
        reservations = new ReservationService(store, floorPlan, new ReversibleProtection(), audit, clock);
        JdbcSessionOrderSource orders = new JdbcSessionOrderSource(jdbc);
        sessions = new TableSessionService(store, floorPlan, orders, audit, clock, metrics);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        qr = new QrEntryService(store, floorPlan, tenantId -> Optional.of("QRTABLE"), limiter, clock);
        walkIn = new WalkInSeatingService(
                qr,
                store,
                sessions,
                (tenantId, accountId) -> blacklisted.contains(accountId),
                limiter,
                clock,
                transactions,
                metrics);
        lifecycle = new ClaimLifecycleService(store, orders, sessions, clock, transactions);

        seedTenancy();
        seedFloorPlan();
    }

    // ------------------------------------------------------------------ opening

    @Test
    @DisplayName("a guest at a free table seats themselves: a claim that occupies the table and names its window")
    void aGuestSeatsThemselves() {
        enableSelfSeat();
        UUID account = account();

        Seating seating = seat(tableOne, account, 2);

        assertThat(seating.created()).isTrue();
        SessionRow session = seating.session();
        assertThat(session.origin()).isEqualTo(SessionOrigin.GUEST_QR);
        assertThat(session.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(session.reservationId()).as("a walk-in names no booking").isNull();
        assertThat(session.partySize()).isEqualTo(2);
        assertThat(session.currency())
                .as("the branch's interim session currency")
                .isEqualTo("UZS");
        assertThat(session.openedBy()).isEqualTo("guest:" + account);
        assertThat(session.openedByAccountId()).isEqualTo(account);
        assertThat(session.claimExpiresAt()).isEqualTo(NOON.plus(Duration.ofMinutes(15)));
        assertThat(session.confirmedAt())
                .as("nothing the restaurant accepted is on it yet")
                .isNull();
        assertThat(session.unconfirmedClaim()).isTrue();

        assertThat(store.findLiveSessionAtTable(TENANT, tableOne))
                .as("the claim occupies the table through the same index staff sessions use")
                .map(SessionRow::id)
                .contains(session.id());

        AuditFact opened = audit.only("dinein.session.opened");
        assertThat(opened.changeDocument().toString())
                .contains("GUEST_QR", "walkIn")
                .as("the audit fact names the claim's origin and window and never the claimant's account id")
                .doesNotContain(account.toString());
    }

    @Test
    @DisplayName("the capability ships off: with no settings row, or with the switch false, nobody can seat themselves")
    void itShipsOff() {
        UUID account = account();
        String menuOnly = exchange(tableOne);

        // No settings row: the branch is VIEW_ONLY, and a VIEW_ONLY code answers every
        // ordering route with the same generic 404.
        ApiException withoutARow = (ApiException) catchThrowable(() -> walkIn.seat(menuOnly, account, 2));
        assertThat(withoutARow.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // A row exists (QR ordering on) but the switch was never turned.
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "ORDER_AND_PAY", null, null, null),
                "manager",
                "QR ordering on"));
        String guest = exchange(tableOne);
        Throwable switchedOff = catchThrowable(() -> walkIn.seat(guest, account, 2));
        assertNotAvailable(switchedOff);

        assertThat(sessionCount()).as("nothing was opened").isZero();
        assertThat(store.findSettings(TENANT, branch).orElseThrow().walkIn().selfSeat())
                .as("ADR 0143: off by default, per location")
                .isFalse();
    }

    @Test
    @DisplayName("self-seating means nothing once the branch has gone back to VIEW_ONLY")
    void itNeedsOrderAndPay() {
        enableSelfSeat();
        String guest = exchange(tableOne);
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "VIEW_ONLY", null, null, null),
                "manager",
                "Menu only tonight"));

        assertNotAvailable(catchThrowable(() -> walkIn.seat(guest, account(), 2)));
        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("the exchange tells a token holder whether they could seat themselves, and nothing more")
    void theExchangeSaysWhetherSelfSeatingIsOffered() {
        UUID account = account();

        assertThat(admission(tableOne).walkInAvailable())
                .as("the switch is off")
                .isFalse();

        enableSelfSeat();
        assertThat(admission(tableOne).walkInAvailable()).isTrue();

        // A confirmed booking starting inside the horizon makes it false, for the
        // same reason the route would refuse.
        confirm(book(tableTwo, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2))));
        assertThat(admission(tableTwo).walkInAvailable()).isFalse();

        // Occupied: openSessionId says so already, and walkInAvailable is false.
        seat(tableOne, account, 2);
        GuestAdmission occupied = admission(tableOne);
        assertThat(occupied.openSessionId()).isNotNull();
        assertThat(occupied.walkInAvailable()).isFalse();
    }

    @Test
    @DisplayName("two guests scanning one free table at once: exactly one session, and the other gets it back")
    void twoGuestsOnOneTable() throws Exception {
        enableSelfSeat();
        String first = exchange(tableOne);
        String second = exchange(tableOne);
        UUID alice = account();
        UUID bob = account();

        List<Outcome2<Seating>> results =
                race(List.of(() -> walkIn.seat(first, alice, 2), () -> walkIn.seat(second, bob, 3)));

        assertThat(results).allMatch(Outcome2::succeeded);
        List<Seating> seatings = results.stream().map(Outcome2::value).toList();
        assertThat(seatings.stream().filter(Seating::created))
                .as("one of them opened it")
                .hasSize(1);
        assertThat(seatings.stream().map(seating -> seating.session().id()).distinct())
                .as("and both are given the same session")
                .hasSize(1);
        assertThat(sessionCount()).as("one row, not two").isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a party of five at a four-seat table is refused; four is seated; a table out of service is not offered")
    void capacityIsStrict() {
        enableSelfSeat();
        UUID account = account();
        String guestAtOne = exchange(tableOne);

        ApiException tooMany = (ApiException) catchThrowable(() -> walkIn.seat(guestAtOne, account, 5));
        assertThat(tooMany.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(tooMany.properties()).containsEntry("seats", 4);
        assertThat(sessionCount()).isZero();

        ApiException none = (ApiException) catchThrowable(() -> walkIn.seat(guestAtOne, account, 0));
        assertThat(none.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(walkIn.seat(guestAtOne, account, 4).created()).isTrue();

        // A token minted while the table was in service, used after it was taken out.
        String guestAtTwo = exchange(tableTwo);
        TableRow two = store.findTable(TENANT, tableTwo).orElseThrow();
        assertThat(store.updateTableStatus(TENANT, tableTwo, two.version(), "OUT_OF_SERVICE", clock.instant()))
                .isTrue();
        assertNotAvailable(catchThrowable(() -> walkIn.seat(guestAtTwo, account(), 2)));
    }

    @Test
    @DisplayName(
            "a confirmed booking holds the table inside the horizon -- and only a confirmed one, and only inside it")
    void reservationHoldsWin() {
        enableSelfSeat();
        // Turnaround is 15 minutes, so a booking requested from T holds from T-15.
        // The horizon is 90 minutes: a hold starting at NOON+89m is inside it, one
        // starting at exactly NOON+90m is not ('[)').
        UUID inside = table("H1", 4);
        UUID boundary = table("H2", 4);
        UUID outside = table("H3", 4);
        UUID requested = table("H4", 4);
        UUID cancelled = table("H5", 4);

        confirm(book(inside, NOON.plus(Duration.ofMinutes(104)), NOON.plus(Duration.ofHours(3))));
        confirm(book(boundary, NOON.plus(Duration.ofMinutes(105)), NOON.plus(Duration.ofHours(3))));
        confirm(book(outside, NOON.plus(Duration.ofHours(4)), NOON.plus(Duration.ofHours(6))));
        book(requested, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2)));
        UUID toCancel = book(cancelled, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2)));
        confirm(toCancel);
        cancel(toCancel);

        assertNotAvailable(catchThrowable(() -> seat(inside, account(), 2)));
        assertThat(seat(boundary, account(), 2).created())
                .as("a hold that starts exactly at the horizon is outside [now, now + horizon)")
                .isTrue();
        assertThat(seat(outside, account(), 2).created()).isTrue();
        assertThat(seat(requested, account(), 2).created())
                .as("a REQUESTED booking holds nothing yet")
                .isTrue();
        assertThat(seat(cancelled, account(), 2).created())
                .as("a CANCELLED booking holds nothing any more")
                .isTrue();
    }

    @Test
    @DisplayName("the horizon is the branch's own number: widen it and a later booking now holds the table")
    void theHorizonIsASetting() {
        enableSelfSeat();
        UUID later = table("L1", 4);
        confirm(book(later, NOON.plus(Duration.ofHours(3)), NOON.plus(Duration.ofHours(5))));

        assertThat(seat(later, account(), 2).created()).isTrue();
        close(later);

        configureWalkIn(new WalkInChange(null, null, 240, null, null, null, null));
        assertNotAvailable(catchThrowable(() -> seat(later, account(), 2)));
    }

    @Test
    @DisplayName(
            "a blacklisted account, a second claim, the daily cap and the branch cap are all refused -- with one and the same answer")
    void everyRefusalReadsTheSame() {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, 2, 3, null, null));
        List<Throwable> refusals = new ArrayList<>();

        // The switch off at another branch (QR ordering on, self-seating never turned) is
        // the same answer too.
        configureOtherTenant(new WalkInChange(false, null, null, null, null, null, null));
        refusals.add(catchThrowable(() -> walkIn.seat(exchangeAt(otherTable), account(), 2)));

        // Blacklisted.
        UUID banned = account();
        blacklisted.add(banned);
        refusals.add(catchThrowable(() -> seat(tableOne, banned, 2)));

        // A second live unconfirmed claim by one account at one branch.
        UUID greedy = account();
        seat(tableOne, greedy, 2);
        refusals.add(catchThrowable(() -> seat(tableTwo, greedy, 2)));

        // The branch cap: two live unconfirmed claims at the branch already.
        UUID other = account();
        UUID t3 = table("C3", 4);
        seat(tableTwo, other, 2);
        refusals.add(catchThrowable(() -> seat(t3, account(), 2)));

        // Held by a booking.
        UUID held = table("C4", 4);
        confirm(book(held, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2))));
        refusals.add(catchThrowable(() -> seat(held, account(), 2)));

        refusals.forEach(WalkInSeatingTests::assertNotAvailable);
        Set<String> shapes = new HashSet<>();
        for (Throwable refusal : refusals) {
            ApiException refused = (ApiException) refusal;
            shapes.add(refused.errorCode() + "|" + refused.getMessage() + "|" + refused.properties());
        }
        assertThat(shapes)
                .as("the response never says which of the reasons it was")
                .hasSize(1);
        assertThat(sessionCount())
                .as("only the two claims that were allowed exist")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the daily cap counts what an account opened in 24 hours, whatever became of it, and lifts after them")
    void theDailyCap() {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, null, 2, null, null));
        UUID account = account();

        seat(tableOne, account, 2);
        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).as("the first lapses").isEqualTo(1);
        seat(tableOne, account, 2);
        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).as("and the second").isEqualTo(1);

        assertNotAvailable(catchThrowable(() -> seat(tableOne, account, 2)));

        clock.advance(Duration.ofHours(24));
        assertThat(seat(tableOne, account, 2).created())
                .as("the day's window has moved on")
                .isTrue();
    }

    @Test
    @DisplayName("the per-token limit is five opens a minute, then a 429 -- and a different token is not affected")
    void thePerTokenRateLimit() {
        // QR ordering on, self-seating off: every call is refused fast, but each one
        // still counts against the token it carried.
        configureWalkIn(new WalkInChange(false, null, null, null, null, null, null));
        UUID account = account();
        String guest = exchange(tableOne);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertNotAvailable(catchThrowable(() -> walkIn.seat(guest, account, 2)));
        }
        ApiException limited = (ApiException) catchThrowable(() -> walkIn.seat(guest, account, 2));
        assertThat(limited.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);

        assertNotAvailable(catchThrowable(() -> walkIn.seat(exchange(tableOne), account, 2)));

        clock.advance(Duration.ofMinutes(2));
        assertNotAvailable(catchThrowable(() -> walkIn.seat(guest, account, 2)));
    }

    @Test
    @DisplayName("the limit comes before the lookup: a flood of a dead token is stopped, not looked up each time")
    void aDeadTokenIsLimitedBeforeItIsLookedUp() {
        String dead = "not-a-live-guest-token-" + UUID.randomUUID();
        UUID account = account();

        for (int attempt = 0; attempt < 5; attempt++) {
            ApiException refused = (ApiException) catchThrowable(() -> walkIn.seat(dead, account, 2));
            assertThat(refused.errorCode())
                    .as("a token nobody minted is refused the way every dead token is")
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        }
        ApiException limited = (ApiException) catchThrowable(() -> walkIn.seat(dead, account, 2));

        assertThat(limited.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("a guest cannot name a table: the token's own table is the only one reachable")
    void aGuestReachesOnlyTheirOwnTable() {
        enableSelfSeat();
        UUID account = account();

        Seating seating = seat(tableOne, account, 2);

        assertThat(sessionTables(seating.session().id())).containsExactly(tableOne);
        assertThat(store.findLiveSessionAtTable(TENANT, tableTwo))
                .as("the neighbouring table is untouched")
                .isEmpty();
    }

    // ------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName(
            "tenants are separate worlds: one tenant's claims do not spend another's branch cap, and a token reaches only its own")
    void tenantIsolation() {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, 1, null, null, null));
        enableSelfSeatAtOtherTenant(1);

        UUID mine = account();
        UUID theirs = accountAtOtherTenant();

        assertThat(seat(tableOne, mine, 2).created()).isTrue();
        Seating other = walkIn.seat(exchangeAt(otherTable), theirs, 2);
        assertThat(other.created())
                .as("tenant A's claim has used tenant A's cap of one and nobody else's")
                .isTrue();
        assertThat(other.session().tenantId()).isEqualTo(OTHER_TENANT);

        assertThat(store.findSession(
                        OTHER_TENANT,
                        store.findLiveSessionAtTable(TENANT, tableOne)
                                .orElseThrow()
                                .id()))
                .as("a session id of one tenant is nothing at another")
                .isEmpty();
        assertThat(store.countLiveUnconfirmedClaims(TENANT, branch)).isEqualTo(1);
        assertThat(store.countLiveUnconfirmedClaims(OTHER_TENANT, otherBranch)).isEqualTo(1);

        // One sweep, both tenants' lapsing claims, each in its own world.
        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).isEqualTo(2);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
        assertThat(store.findLiveSessionAtTable(OTHER_TENANT, otherTable)).isEmpty();
    }

    // ------------------------------------------------------- the caps, under real threads

    @Test
    @DisplayName(
            "one account firing claims at six different free tables of one branch ends with exactly one live claim")
    void oneAccountSixTables() throws Exception {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, 50, 20, null, null));
        List<UUID> tables = tables(6);
        UUID account = account();
        List<String> guests = tables.stream().map(this::exchange).toList();

        List<Outcome2<Seating>> results = race(guests.stream()
                .<Callable<Seating>>map(guest -> () -> walkIn.seat(guest, account, 2))
                .toList());

        assertThat(results.stream().filter(Outcome2::succeeded))
                .as("exactly one of the six claims was opened")
                .hasSize(1);
        results.stream().filter(result -> !result.succeeded()).forEach(result -> assertNotAvailable(result.failure()));
        assertThat(store.countLiveUnconfirmedClaims(TENANT, branch)).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the account cap is held by the index, not the lock: claims opened with no settings lock at all still end with one")
    void theIndexHoldsTheAccountCapWithoutTheLock() throws Exception {
        List<UUID> tables = tables(6);
        UUID account = account();
        Instant expiresAt = NOON.plus(Duration.ofMinutes(15));

        // Straight to the service that writes the row: no settings lock, no counting.
        List<Outcome2<SessionRow>> results = race(tables.stream()
                .<Callable<SessionRow>>map(table -> () -> transactions.execute(status -> sessions.open(
                        new TableSessionService.OpenSession(
                                TENANT,
                                BRAND,
                                branch,
                                null,
                                List.of(table),
                                2,
                                "UZS",
                                "guest:" + account,
                                new TableSessionService.Claim(account, expiresAt)),
                        "Opened by the guest")))
                .toList());

        assertThat(results.stream().filter(Outcome2::succeeded))
                .as("whatever any transaction counted, the database refuses a second live unconfirmed claim")
                .hasSize(1);
        results.stream().filter(result -> !result.succeeded()).forEach(result -> assertNotAvailable(result.failure()));
    }

    @Test
    @DisplayName("six accounts, each at a different table, against a branch cap of two: exactly two live claims")
    void theBranchCapUnderRealThreads() throws Exception {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, 2, 20, null, null));
        List<UUID> tables = tables(6);
        List<Callable<Seating>> calls = new ArrayList<>();
        for (UUID table : tables) {
            String guest = exchange(table);
            UUID account = account();
            calls.add(() -> walkIn.seat(guest, account, 2));
        }

        List<Outcome2<Seating>> results = race(calls);

        assertThat(results.stream().filter(Outcome2::succeeded))
                .as("the cap is counted under the settings-row lock, so six at once cannot all read zero")
                .hasSize(2);
        results.stream().filter(result -> !result.succeeded()).forEach(result -> assertNotAvailable(result.failure()));
        assertThat(store.countLiveUnconfirmedClaims(TENANT, branch)).isEqualTo(2);
    }

    // ------------------------------------------------- the locks, shown to be held

    @Test
    @DisplayName("a guest opening waits for the branch's settings row: the lock the caps are counted under")
    void aClaimQueuesBehindTheSettingsRow() throws Exception {
        enableSelfSeat();
        String guest = exchange(tableOne);
        UUID account = account();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            execute(
                    holder,
                    "SELECT 1 FROM dinein.location_settings WHERE location_id = '" + branch + "' FOR NO KEY UPDATE");

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Seating> claim = pool.submit(() -> walkIn.seat(guest, account, 2));
                assertStillWaiting(claim);
                holder.commit();
                assertThat(claim.get(10, TimeUnit.SECONDS).created()).isTrue();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("a guest opening waits for the table row, and a staff seating does too")
    // The holder takes FOR NO KEY UPDATE, not FOR UPDATE: a foreign key check on session_tables takes
    // FOR KEY SHARE on the table row, which FOR UPDATE would block by itself and so make this pass
    // whether or not the code under test locked anything. FOR NO KEY UPDATE is blocked only by the
    // explicit FOR UPDATE the code takes.
    void aClaimAndAStaffSeatingQueueBehindTheTableRow() throws Exception {
        enableSelfSeat();
        String guest = exchange(tableOne);
        UUID account = account();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "SELECT 1 FROM dinein.tables WHERE id = '" + tableOne + "' FOR NO KEY UPDATE");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Seating> claim = pool.submit(() -> walkIn.seat(guest, account, 2));
                Future<SessionRow> staff = pool.submit(() -> openWalkIn(tableOne, 2));
                assertStillWaiting(claim);
                assertStillWaiting(staff);
                holder.commit();

                for (Future<?> future : List.of(claim, staff)) {
                    try {
                        future.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException refused) {
                        assertThat(refused.getCause()).isInstanceOf(ApiException.class);
                    }
                }
                assertThat(sessionCount())
                        .as("one party per table, whichever got the lock")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("confirming a booking and amending a confirmed one wait for the table row a guest opening takes")
    void aBookingQueuesBehindTheTableRow() throws Exception {
        UUID booked = book(tableOne, NOON.plus(Duration.ofHours(5)), NOON.plus(Duration.ofHours(7)));
        ReservationRowVersion pending = versionOf(booked);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "SELECT 1 FROM dinein.tables WHERE id = '" + tableOne + "' FOR NO KEY UPDATE");

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> confirming = pool.submit(() -> transactions.executeWithoutResult(status -> reservations.move(
                        TENANT, branch, booked, ReservationStatus.CONFIRMED, pending.version(), "host", "Table free")));
                assertStillWaiting(confirming);
                holder.commit();
                confirming.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(statusOf(booked)).isEqualTo(ReservationStatus.CONFIRMED);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "SELECT 1 FROM dinein.tables WHERE id = '" + tableOne + "' FOR NO KEY UPDATE");

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                int version = versionOf(booked).version();
                Future<?> amending = pool.submit(() -> transactions.executeWithoutResult(status -> reservations.amend(
                        TENANT,
                        branch,
                        booked,
                        3,
                        NOON.plus(Duration.ofHours(5)),
                        NOON.plus(Duration.ofHours(7)),
                        List.of(tableOne),
                        null,
                        null,
                        null,
                        version,
                        "host",
                        "Party of three")));
                assertStillWaiting(amending);
                holder.commit();
                amending.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName(
            "a host confirming over a table a guest has already taken sees it occupied; a guest after the host is refused")
    void hostAndGuestSeeTheWinnersState() {
        enableSelfSeat();
        UUID guestFirst = table("W1", 4);
        UUID hostFirst = table("W2", 4);
        UUID account = account();
        UUID otherAccount = account();

        // The guest first: the host is told the table is occupied now, and the booking is
        // confirmed all the same -- confirming never bumps a seated party.
        seat(guestFirst, account, 2);
        UUID bookingOverClaim = book(guestFirst, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2)));
        ReservationService.ReservationOutcome confirmed = confirmReporting(bookingOverClaim);
        assertThat(confirmed.tableOccupiedNow()).isTrue();
        assertThat(confirmed.reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(store.findLiveSessionAtTable(TENANT, guestFirst))
                .as("the party stays")
                .isPresent();

        // The host first: the guest is refused, and the host was told it is free.
        UUID bookingFirst = book(hostFirst, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2)));
        assertThat(confirmReporting(bookingFirst).tableOccupiedNow()).isFalse();
        assertNotAvailable(catchThrowable(() -> seat(hostFirst, otherAccount, 2)));
    }

    @Test
    @DisplayName(
            "host and guest racing for one table: whoever is second sees the first's state, never both succeeding blind")
    void hostAndGuestRace() throws Exception {
        enableSelfSeat();
        int guestWon = 0;
        int hostWon = 0;

        for (int round = 0; round < 12; round++) {
            UUID raced = table("R" + round, 4);
            UUID booking = book(raced, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2)));
            String guest = exchange(raced);
            // A fresh account each round: the previous round's claim is still live.
            UUID claimant = account();

            List<Outcome2<Object>> results =
                    race(List.of(() -> walkIn.seat(guest, claimant, 2), () -> confirmReporting(booking)));

            Outcome2<Object> seating = results.get(0);
            Outcome2<Object> confirming = results.get(1);
            assertThat(confirming.succeeded())
                    .as("the host's confirmation always lands")
                    .isTrue();
            ReservationService.ReservationOutcome outcome =
                    (ReservationService.ReservationOutcome) Objects.requireNonNull(confirming.value());
            boolean occupiedNow = Boolean.TRUE.equals(outcome.tableOccupiedNow());

            if (seating.succeeded()) {
                guestWon++;
                assertThat(occupiedNow)
                        .as("the guest got in first, so the host must have been told the table is occupied")
                        .isTrue();
            } else {
                hostWon++;
                assertNotAvailable(seating.failure());
                assertThat(occupiedNow)
                        .as("the host was first, so nobody was sitting there when they confirmed")
                        .isFalse();
            }
        }
        assertThat(guestWon + hostWon).isEqualTo(12);
    }

    // --------------------------------------------------------------- the lifecycle

    @Test
    @DisplayName("a claim with no round lapses when its window ends -- not before -- and gives the table back")
    void aClaimWithNoRoundLapses() {
        enableSelfSeat();
        UUID account = account();
        String guestToken = exchange(tableOne);
        Seating seating = walkIn.seat(guestToken, account, 2);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(lifecycle.sweepOnce())
                .as("one second before the window ends")
                .isZero();
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(lifecycle.sweepOnce()).isEqualTo(1);

        SessionRow lapsed = store.findSession(TENANT, seating.session().id()).orElseThrow();
        assertThat(lapsed.status()).isEqualTo(SessionStatus.CLOSED);
        assertThat(lapsed.closeReasonCode()).isEqualTo("CLAIM_LAPSED");
        assertThat(lapsed.closedAt()).isEqualTo(clock.instant());
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne))
                .as("the table is back in the room")
                .isEmpty();
        assertThat(tokenIsLive(guestToken))
                .as("closing a claim ends the guest token minted at its table, exactly as a staff close does")
                .isFalse();
        assertThat(audit.count("dinein.session.claim-lapsed")).isEqualTo(1);
        assertThat(audit.count("dinein.session.closed")).isEqualTo(1);

        assertThat(seat(tableOne, account(), 2).created())
                .as("and it can be claimed again")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a round the restaurant had already accepted when it was attached makes the claim an ordinary session at once")
    void anAcceptedRoundConfirmsAtOnce() {
        enableSelfSeat();
        UUID account = account();
        Seating seating = seat(tableOne, account, 2);
        UUID order = seedDineInOrder("A-1", 30_000, account, "CONFIRMED");

        transactions.executeWithoutResult(
                status -> sessions.addRound(TENANT, seating.session().id(), order, account, "guest:t", "Placed"));

        SessionRow confirmed = store.findSession(TENANT, seating.session().id()).orElseThrow();
        assertThat(confirmed.confirmedAt()).isEqualTo(clock.instant());
        assertThat(confirmed.confirmedBy()).isEqualTo("round:" + order);
        assertThat(confirmed.unconfirmedClaim()).isFalse();
        assertThat(audit.count("dinein.session.claim-confirmed")).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        assertThat(lifecycle.sweepOnce())
                .as("an ordinary session is never lapsed")
                .isZero();
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isPresent();
    }

    @Test
    @DisplayName(
            "a round still authorizing confirms nothing and lapses nothing until the claim's window plus the deferral bound")
    void aRoundInFlightIsDeferredExactlyOnceAndNeverRenewed() {
        enableSelfSeat();
        UUID account = account();
        Seating seating = seat(tableOne, account, 2);
        UUID order = seedDineInOrder("P-1", 45_000, account, "PAYMENT_AUTHORIZING");
        transactions.executeWithoutResult(
                status -> sessions.addRound(TENANT, seating.session().id(), order, account, "guest:t", "Placed"));

        assertThat(store.findSession(TENANT, seating.session().id())
                        .orElseThrow()
                        .unconfirmedClaim())
                .as("attaching alone confirms nothing")
                .isTrue();

        // Past the window: deferred, not lapsed.
        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).isZero();
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isPresent();

        // Sweeping again does not move the bound: 29 minutes past the window is still
        // inside it (the window ended at +15m, the bound is +45m) ...
        clock.advance(Duration.ofMinutes(28));
        assertThat(lifecycle.sweepOnce()).isZero();
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isPresent();

        // ... and 30 is not, however many sweeps came before.
        clock.advance(Duration.ofMinutes(1));
        assertThat(lifecycle.sweepOnce())
                .as("a round still in flight past claim_expires_at + 30m lapses the claim")
                .isEqualTo(1);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
        assertThat(store.findSession(TENANT, seating.session().id())
                        .orElseThrow()
                        .closeReasonCode())
                .isEqualTo("CLAIM_LAPSED");
    }

    @Test
    @DisplayName("a round that fails while the claim is deferred lapses it at once; one that is accepted confirms it")
    void aDeferredClaimFollowsTheRound() {
        enableSelfSeat();
        UUID failer = account();
        UUID payer = account();
        Seating failing = seat(tableOne, failer, 2);
        Seating paying = seat(tableTwo, payer, 2);
        UUID failingOrder = seedDineInOrder("F-1", 10_000, failer, "PAYMENT_AUTHORIZING");
        UUID payingOrder = seedDineInOrder("F-2", 20_000, payer, "PAYMENT_AUTHORIZING");
        transactions.executeWithoutResult(status -> {
            sessions.addRound(TENANT, failing.session().id(), failingOrder, failer, "guest:t", "Placed");
            sessions.addRound(TENANT, paying.session().id(), payingOrder, payer, "guest:t", "Placed");
        });

        clock.advance(Duration.ofMinutes(20));
        assertThat(lifecycle.sweepOnce()).as("both deferred").isZero();

        setOrderStatus(failingOrder, "PAYMENT_FAILED");
        setOrderStatus(payingOrder, "CONFIRMED");
        clock.advance(Duration.ofMinutes(1));
        assertThat(lifecycle.sweepOnce()).isEqualTo(2);

        assertThat(store.findSession(TENANT, failing.session().id())
                        .orElseThrow()
                        .status())
                .isEqualTo(SessionStatus.CLOSED);
        SessionRow settled = store.findSession(TENANT, paying.session().id()).orElseThrow();
        assertThat(settled.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(settled.confirmedBy()).isEqualTo("round:" + payingOrder);
    }

    @Test
    @DisplayName(
            "only failed, rejected, expired or cancelled rounds on a claim: it lapses at once, not after the deferral")
    void failedRoundsDoNotDefer() {
        enableSelfSeat();
        UUID account = account();
        Seating seating = seat(tableOne, account, 2);
        UUID rejected = seedDineInOrder("X-1", 10_000, account, "REJECTED");
        transactions.executeWithoutResult(
                status -> sessions.addRound(TENANT, seating.session().id(), rejected, account, "guest:t", "Placed"));

        clock.advance(Duration.ofMinutes(15));
        assertThat(lifecycle.sweepOnce()).isEqualTo(1);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
    }

    @Test
    @DisplayName(
            "a lapse and an attach racing for one claim have one outcome: never a round on a table that was given back")
    void aLapseRacingAnAttachHasOneOutcome() throws Exception {
        enableSelfSeat();
        for (int round = 0; round < 12; round++) {
            clock.advance(Duration.ofHours(25));
            UUID account = account();
            UUID table = table("Z" + round, 4);
            Seating seating = seat(table, account, 2);
            UUID order = seedDineInOrder("Z-" + round, 12_000, account, "CONFIRMED");
            clock.advance(Duration.ofMinutes(16));

            List<Outcome2<Object>> results = race(List.of(
                    () -> {
                        lifecycle.sweepOnce();
                        return "swept";
                    },
                    () -> transactions.execute(status ->
                            sessions.addRound(TENANT, seating.session().id(), order, account, "guest:t", "Placed"))));

            SessionRow after = store.findSession(TENANT, seating.session().id()).orElseThrow();
            boolean attached = !sessions.rounds(TENANT, seating.session().id()).isEmpty();
            if (after.status() == SessionStatus.CLOSED) {
                assertThat(attached)
                        .as("round %d: the claim was given back, so the round was refused", round)
                        .isFalse();
                assertThat(results.get(1).succeeded()).isFalse();
            } else {
                assertThat(attached)
                        .as("round %d: the claim lived, so the round is on it and it is confirmed", round)
                        .isTrue();
                assertThat(after.unconfirmedClaim()).isFalse();
                assertThat(results.get(1).succeeded()).isTrue();
            }
        }
    }

    @Test
    @DisplayName(
            "tap and leave: a claim moved to the bill lapses through OPEN at its window; a staff-handled one is left alone")
    void tapAndLeave() {
        enableSelfSeat();
        UUID tapper = account();
        UUID handled = account();
        Seating tapped = seat(tableOne, tapper, 2);
        Seating staffMoved = seat(tableTwo, handled, 2);

        // The guest's own token cannot get a claim to the bill ...
        ApiException refused = (ApiException) catchThrowable(() -> transactions.execute(status -> sessions.moveByGuest(
                TENANT,
                tapped.session().id(),
                SessionStatus.BILL_REQUESTED,
                tapped.session().version(),
                tableOne,
                "Requested")));
        assertThat(refused.properties()).containsEntry("conflict", "CLAIM_UNCONFIRMED");

        // ... so the test puts the row there directly, as a race would.
        jdbc.sql("UPDATE dinein.table_sessions SET status = 'BILL_REQUESTED' WHERE id = :id")
                .param("id", tapped.session().id())
                .update();
        // A member of staff moving the other past OPEN has taken charge of it.
        SessionRow moved = transactions.execute(status -> sessions.move(
                TENANT,
                staffMoved.session().id(),
                SessionStatus.BILL_REQUESTED,
                staffMoved.session().version(),
                null,
                "waiter",
                "Table asked"));
        assertThat(moved.confirmedBy()).isEqualTo("waiter");
        assertThat(moved.unconfirmedClaim()).isFalse();

        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).isEqualTo(1);

        SessionRow lapsed = store.findSession(TENANT, tapped.session().id()).orElseThrow();
        assertThat(lapsed.status())
                .as("a BILL_REQUESTED claim has no edge to CLOSED, so the lapse goes by way of OPEN")
                .isEqualTo(SessionStatus.CLOSED);
        assertThat(lapsed.closeReasonCode()).isEqualTo("CLAIM_LAPSED");
        assertThat(audit.count("dinein.session.open"))
                .as("the price: one intermediate reopening fact")
                .isEqualTo(1);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
        assertThat(store.findLiveSessionAtTable(TENANT, tableTwo))
                .as("the staff-handled table is left alone")
                .isPresent();

        assertThat(seat(tableOne, tapper, 2).created())
                .as("and it can be claimed again")
                .isTrue();
    }

    @Test
    @DisplayName("a claim being settled lapses too, and so does one the guest moved on a second tap")
    void aSettlingClaimLapses() {
        enableSelfSeat();
        Seating seating = seat(tableOne, account(), 2);
        jdbc.sql("UPDATE dinein.table_sessions SET status = 'SETTLING' WHERE id = :id")
                .param("id", seating.session().id())
                .update();

        clock.advance(Duration.ofMinutes(16));

        assertThat(lifecycle.sweepOnce()).isEqualTo(1);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
    }

    @Test
    @DisplayName("the sweeper's selector sees a claim in every live status, not only OPEN")
    void theSweepSelectsEveryLiveStatus() {
        enableSelfSeat();
        List<String> statuses = List.of("OPEN", "BILL_REQUESTED", "SETTLING");
        List<UUID> tables = tables(3);
        for (int i = 0; i < 3; i++) {
            Seating seating = seat(tables.get(i), account(), 2);
            jdbc.sql("UPDATE dinein.table_sessions SET status = :status WHERE id = :id")
                    .param("status", statuses.get(i))
                    .param("id", seating.session().id())
                    .update();
        }
        clock.advance(Duration.ofMinutes(16));

        assertThat(store.dueClaims(clock.instant(), 100))
                .extracting(session -> session.status().name())
                .containsExactlyInAnyOrder("OPEN", "BILL_REQUESTED", "SETTLING");
    }

    @Test
    @DisplayName("rotating the table's printed code ends a claim's guest token")
    void rotationRevokesTheClaimsToken() {
        enableSelfSeat();
        String guest = exchange(tableOne);
        walkIn.seat(guest, account(), 2);
        assertThat(tokenIsLive(guest)).isTrue();

        int version = store.findTable(TENANT, tableOne).orElseThrow().version();
        transactions.executeWithoutResult(
                status -> floorPlan.rotateQrToken(TENANT, branch, tableOne, version, "manager", "Code leaked"));

        assertThat(tokenIsLive(guest)).isFalse();
    }

    // ------------------------------------------------------------ the staff surface

    @Test
    @DisplayName(
            "staff still seat anyone anywhere -- over a hold, over capacity, with the switch off -- and the audit fact says so")
    void staffKeepEveryOverride() {
        // Nothing enabled: the staff path never asks.
        UUID held = table("S1", 2);
        confirm(book(held, NOON.plus(Duration.ofMinutes(30)), NOON.plus(Duration.ofHours(2))));

        SessionRow over = openWalkIn(held, 6);

        assertThat(over.origin()).isEqualTo(SessionOrigin.STAFF);
        assertThat(over.openedByAccountId()).isNull();
        assertThat(over.claimExpiresAt()).isNull();
        assertThat(over.unconfirmedClaim())
                .as("a staff session is an ordinary session")
                .isFalse();
        AuditFact fact = audit.only("dinein.session.opened");
        assertThat(fact.changeDocument().toString()).contains("bookedOver", "overCapacity", "STAFF");
        assertThat(afterOf(fact, "bookedOver")).isEqualTo("true");
        assertThat(afterOf(fact, "overCapacity")).isEqualTo("true");

        // And an ordinary seating says it was neither.
        audit.facts.clear();
        openWalkIn(tableOne, 2);
        AuditFact plain = audit.only("dinein.session.opened");
        assertThat(afterOf(plain, "bookedOver")).isEqualTo("false");
        assertThat(afterOf(plain, "overCapacity")).isEqualTo("false");
    }

    @Test
    @DisplayName("staff confirm a claim to keep the table; a staff move past OPEN confirms it; closing one releases it")
    void staffConfirmAndClose() {
        enableSelfSeat();
        Seating keep = seat(tableOne, account(), 2);
        Seating release = seat(tableTwo, account(), 2);

        SessionRow confirmed = transactions.execute(status -> sessions.confirmClaim(
                TENANT, branch, keep.session().id(), keep.session().version(), "manager", "Guest is at the bar"));
        assertThat(confirmed.confirmedBy()).isEqualTo("manager");
        assertThat(confirmed.confirmedAt()).isEqualTo(clock.instant());
        assertThat(confirmed.version()).isEqualTo(keep.session().version() + 1);

        ApiException again = (ApiException) catchThrowable(() -> transactions.execute(status ->
                sessions.confirmClaim(TENANT, branch, keep.session().id(), confirmed.version(), "manager", "Again")));
        assertThat(again.properties()).containsEntry("conflict", "NOT_AN_UNCONFIRMED_CLAIM");

        ApiException stale = (ApiException) catchThrowable(() -> transactions.execute(status ->
                sessions.confirmClaim(TENANT, branch, release.session().id(), 99, "manager", "Stale")));
        assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);

        ApiException notAClaim =
                (ApiException) catchThrowable(() -> transactions.execute(status -> sessions.confirmClaim(
                        TENANT, branch, openWalkIn(table("S9", 2), 2).id(), 1, "manager", "Not a claim")));
        assertThat(notAClaim.properties()).containsEntry("conflict", "NOT_AN_UNCONFIRMED_CLAIM");

        ApiException elsewhere =
                (ApiException) catchThrowable(() -> transactions.execute(status -> sessions.confirmClaim(
                        TENANT,
                        otherBranch,
                        release.session().id(),
                        release.session().version(),
                        "manager",
                        "Wrong branch")));
        assertThat(elsewhere.errorCode())
                .as("a branch manager's grant is for one branch")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        SessionRow closed = transactions.execute(status -> sessions.move(
                TENANT,
                release.session().id(),
                SessionStatus.CLOSED,
                release.session().version(),
                null,
                "waiter",
                "Left"));
        assertThat(closed.status()).isEqualTo(SessionStatus.CLOSED);
        assertThat(closed.confirmedAt())
                .as("closing releases the table; it confirms nothing")
                .isNull();
        assertThat(store.findLiveSessionAtTable(TENANT, tableTwo)).isEmpty();
    }

    @Test
    @DisplayName("switching the capability off stops new claims; the ones already open lapse by their window")
    void rollbackIsTheSetting() {
        enableSelfSeat();
        UUID account = account();
        seat(tableOne, account, 2);

        configureWalkIn(new WalkInChange(false, null, null, null, null, null, null));

        assertNotAvailable(catchThrowable(() -> seat(tableTwo, account(), 2)));
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isPresent();
        clock.advance(Duration.ofMinutes(16));
        assertThat(lifecycle.sweepOnce()).isEqualTo(1);
        assertThat(store.findLiveSessionAtTable(TENANT, tableOne)).isEmpty();
    }

    // ------------------------------------------------------------------ settings

    @Test
    @DisplayName("settings: a never-configured branch is version 0, the first write says so, a stale one is refused")
    void settingsAreVersioned() {
        UUID fresh = insertLocation("EAST", "east");
        assertThat(floorPlan.settings(TENANT, BRAND, fresh).version()).isZero();

        FloorPlanService.BranchSettings request = new FloorPlanService.BranchSettings(
                TENANT,
                BRAND,
                fresh,
                "ORDER_AND_PAY",
                null,
                null,
                null,
                new WalkInChange(true, 20, 60, 3, 2, 10, "UZS"));

        ApiException stale = (ApiException) catchThrowable(
                () -> transactions.execute(status -> floorPlan.configure(request, 5, "manager", "Wrong guess")));
        assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);

        var saved = transactions.execute(status -> floorPlan.configure(request, 0, "manager", "First time"));
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.walkIn().selfSeat()).isTrue();
        assertThat(saved.walkIn().claimTtlMinutes()).isEqualTo(20);
        assertThat(saved.walkIn().horizonMinutes()).isEqualTo(60);
        assertThat(saved.walkIn().maxUnconfirmed()).isEqualTo(3);
        assertThat(saved.walkIn().dailyClaimsPerAccount()).isEqualTo(2);
        assertThat(saved.walkIn().paymentDeferMinutes()).isEqualTo(10);

        ApiException second = (ApiException) catchThrowable(
                () -> transactions.execute(status -> floorPlan.configure(request, 0, "manager", "Same guess again")));
        assertThat(second.errorCode())
                .as("two managers configuring a never-configured branch produce one row and one refusal")
                .isEqualTo(ErrorCode.STALE_VERSION);

        AuditFact fact = audit.facts.stream()
                .filter(f -> "dinein.settings.configured".equals(f.actionCode()))
                .reduce((first, last) -> last)
                .orElseThrow();
        assertThat(fact.changeDocument().toString()).contains("walkInSelfSeat");
    }

    // -------------------------------------------------------------------- erasure

    @Test
    @DisplayName(
            "erasing a customer lapses their live claim and clears their id from every session of theirs that is over")
    void erasureClearsTheClaimant() {
        enableSelfSeat();
        configureWalkIn(new WalkInChange(null, null, null, null, 5, null, null));
        UUID account = account();
        Seating confirmed = seat(tableOne, account, 2);
        UUID order = seedDineInOrder("E-1", 5_000, account, "CONFIRMED");
        transactions.executeWithoutResult(
                status -> sessions.addRound(TENANT, confirmed.session().id(), order, account, "guest:t", "Placed"));
        Seating lapsing = seat(tableTwo, account, 2);

        transactions.executeWithoutResult(
                status -> new DineInErasureParticipant(store, sessions).erase(TENANT, account));

        assertThat(store.findSession(TENANT, lapsing.session().id())
                        .orElseThrow()
                        .status())
                .as("an erased customer's provisional claim does not keep a table from the room")
                .isEqualTo(SessionStatus.CLOSED);
        List<UUID> remaining = jdbc.sql("SELECT opened_by_account_id FROM dinein.table_sessions "
                        + "WHERE opened_by_account_id IS NOT NULL")
                .query(UUID.class)
                .list();
        assertThat(remaining).as("no session still names the account").isEmpty();
        // The guest route stores 'guest:<accountId>' in opened_by too (it is NOT NULL, so it
        // is neutralised rather than nulled): clearing only the dedicated column would leave
        // the account id in the same row.
        assertThat(jdbc.sql("SELECT opened_by FROM dinein.table_sessions "
                                + "WHERE position(:account IN opened_by) > 0")
                        .param("account", account.toString())
                        .query(String.class)
                        .list())
                .as("no session's opened_by still carries the account id")
                .isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM dinein.table_sessions WHERE opened_by = 'guest:erased'")
                        .query(Integer.class)
                        .single())
                .as("both of the customer's sessions, the confirmed one and the lapsed one")
                .isEqualTo(2);
        assertThat(store.findSession(TENANT, confirmed.session().id())
                        .orElseThrow()
                        .origin())
                .as("the session itself, its bill and its history stay")
                .isEqualTo(SessionOrigin.GUEST_QR);

        // Idempotent under retry.
        transactions.executeWithoutResult(
                status -> new DineInErasureParticipant(store, sessions).erase(TENANT, account));
    }

    // -------------------------------------------------------------- vocabulary

    @Test
    @DisplayName("every order status the database allows is classified exactly once as accepted, in flight or failed")
    void everyOrderStatusIsClassified() {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_order_status' AND conrelid = 'ordering.orders'::regclass
                """).query(String.class).single();
        Set<String> inDatabase = new HashSet<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (matcher.find()) {
            inDatabase.add(matcher.group(1));
        }

        assertThat(inDatabase).isNotEmpty();
        assertThat(RoundStatuses.known())
                .as("a status added to ck_order_status must be placed here on purpose")
                .containsExactlyInAnyOrderElementsOf(inDatabase);
        for (String status : inDatabase) {
            int classes = (RoundStatuses.accepted(status) ? 1 : 0)
                    + (RoundStatuses.inFlight(status) ? 1 : 0)
                    + (RoundStatuses.failed(status) ? 1 : 0);
            assertThat(classes).as(status + " is in exactly one class").isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "the counters carry bounded labels only: outcome, and on a refusal its class -- never a tenant, table or account")
    void metricsAreBounded() {
        enableSelfSeat();
        UUID account = account();
        seat(tableOne, account, 2);
        assertNotAvailable(catchThrowable(() -> seat(tableTwo, account, 2)));
        clock.advance(Duration.ofMinutes(16));
        lifecycle.sweepOnce();

        var registry = metrics.registry();
        assertThat(registry.get(ClaimMetrics.CLAIMS)
                        .tag("outcome", "opened")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get(ClaimMetrics.CLAIMS)
                        .tag("outcome", "lapsed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get(ClaimMetrics.CLAIMS)
                        .tag("outcome", "refused")
                        .tag("reason", "ACCOUNT_CAP")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        registry.getMeters()
                .forEach(meter -> meter.getId().getTags().forEach(tag -> {
                    assertThat(tag.getKey()).isIn("outcome", "reason");
                    assertThat(tag.getValue()).doesNotContain(account.toString(), TENANT.toString());
                }));
    }

    // ------------------------------------------------------------------ helpers

    private Seating seat(UUID tableId, UUID account, int partySize) {
        return walkIn.seat(exchange(tableId), account, partySize);
    }

    /** A fresh guest token at the table, from its one printed code (rotating it would end the others). */
    private String exchange(UUID tableId) {
        return admission(tableId).guestToken();
    }

    private String exchangeAt(UUID tableId) {
        return exchange(tableId);
    }

    private GuestAdmission admission(UUID tableId) {
        String code = printed.computeIfAbsent(tableId, this::issueToken);
        return transactions.execute(status -> qr.exchange(code));
    }

    private boolean tokenIsLive(String guestToken) {
        return catchThrowable(() -> qr.resolve(guestToken)) == null;
    }

    private String issueToken(UUID tableId) {
        TableRow row = store.findTable(tableId.equals(otherTable) ? OTHER_TENANT : TENANT, tableId)
                .orElseThrow();
        UUID tenant = row.tenantId();
        return transactions
                .execute(status -> floorPlan.rotateQrToken(
                        tenant, row.locationId(), tableId, row.version(), "manager", "First printing"))
                .plaintext();
    }

    private void enableSelfSeat() {
        configureWalkIn(new WalkInChange(true, null, null, null, null, null, null));
    }

    private void configureWalkIn(WalkInChange change) {
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "ORDER_AND_PAY", null, null, null, change),
                "manager",
                "Self-seating"));
    }

    private void enableSelfSeatAtOtherTenant(int maxUnconfirmed) {
        configureOtherTenant(new WalkInChange(true, null, null, maxUnconfirmed, null, null, null));
    }

    private void configureOtherTenant(WalkInChange change) {
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(
                        OTHER_TENANT, OTHER_BRAND, otherBranch, "ORDER_AND_PAY", null, null, null, change),
                "manager",
                "Self-seating"));
    }

    private UUID account() {
        return accountIn(TENANT);
    }

    private UUID accountAtOtherTenant() {
        return accountIn(OTHER_TENANT);
    }

    private UUID accountIn(UUID tenant) {
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, 'ACTIVE', now(), now())
                """).param("id", account).param("tenantId", tenant).update();
        return account;
    }

    private SessionRow openWalkIn(UUID tableId, int partySize) {
        return transactions.execute(status -> sessions.open(
                new TableSessionService.OpenSession(
                        TENANT, BRAND, branch, null, List.of(tableId), partySize, "UZS", "waiter"),
                "Walk-in"));
    }

    private void close(UUID tableId) {
        SessionRow live = store.findLiveSessionAtTable(TENANT, tableId).orElseThrow();
        transactions.executeWithoutResult(status ->
                sessions.move(TENANT, live.id(), SessionStatus.CLOSED, live.version(), null, "waiter", "Left"));
    }

    private UUID book(UUID tableId, Instant from, Instant to) {
        return transactions
                .execute(status -> reservations.request(new ReservationService.NewReservation(
                        TENANT,
                        BRAND,
                        branch,
                        null,
                        "Dilnoza",
                        "998901234567",
                        null,
                        null,
                        4,
                        from,
                        to,
                        List.of(tableId),
                        channelId,
                        "host")))
                .id();
    }

    private void confirm(UUID reservationId) {
        confirmReporting(reservationId);
    }

    private ReservationService.ReservationOutcome confirmReporting(UUID reservationId) {
        int version = versionOf(reservationId).version();
        return transactions.execute(status -> reservations.moveReporting(
                TENANT, branch, reservationId, ReservationStatus.CONFIRMED, version, "host", "Table available"));
    }

    private void cancel(UUID reservationId) {
        int version = versionOf(reservationId).version();
        transactions.executeWithoutResult(status -> reservations.move(
                TENANT, branch, reservationId, ReservationStatus.CANCELLED, version, "host", "Guest called"));
    }

    private record ReservationRowVersion(int version) {}

    private ReservationRowVersion versionOf(UUID reservationId) {
        return new ReservationRowVersion(
                store.findReservation(TENANT, reservationId).orElseThrow().version());
    }

    private ReservationStatus statusOf(UUID reservationId) {
        return store.findReservation(TENANT, reservationId).orElseThrow().status();
    }

    private long sessionCount() {
        return jdbc.sql("SELECT count(*) FROM dinein.table_sessions")
                .query(Long.class)
                .single();
    }

    private List<UUID> sessionTables(UUID sessionId) {
        return jdbc.sql("SELECT table_id FROM dinein.session_tables WHERE session_id = :id")
                .param("id", sessionId)
                .query(UUID.class)
                .list();
    }

    private void setOrderStatus(UUID orderId, String status) {
        jdbc.sql("""
                UPDATE ordering.orders
                   SET status = :status,
                       confirmed_at = CASE WHEN :status IN ('CONFIRMED', 'PREPARING', 'READY', 'FULFILLING', 'COMPLETED')
                                           THEN now() ELSE confirmed_at END
                 WHERE id = :id
                """).param("status", status).param("id", orderId).update();
    }

    private List<UUID> tables(int count) {
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            made.add(table("M" + i, 4));
        }
        return made;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The {@code after} side of one field of an audit fact's change document. */
    private static String afterOf(AuditFact fact, String field) {
        Map<?, ?> change =
                (Map<?, ?>) Objects.requireNonNull(fact.changeDocument().get(field), field);
        return String.valueOf(change.get("after"));
    }

    private static void assertStillWaiting(Future<?> work) throws InterruptedException {
        Thread.sleep(500);
        assertThat(work.isDone())
                .as("it is queued behind a row lock somebody else holds, so it cannot have finished")
                .isFalse();
    }

    private static void assertNotAvailable(@Nullable Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException refused = (ApiException) Objects.requireNonNull(thrown);
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(refused.properties()).containsEntry("conflict", "TABLE_NOT_AVAILABLE");
    }

    /** One call's result: a value or the throwable it failed with. */
    private record Outcome2<T>(@Nullable T value, @Nullable Throwable failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    /** Releases every call at once, on its own thread and connection, and collects what each returned. */
    private <T> List<Outcome2<T>> race(List<? extends Callable<? extends T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome2<T>>> futures = new ArrayList<>();
            for (Callable<? extends T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome2<T>(call.call(), null);
                    } catch (Throwable failure) {
                        return new Outcome2<T>(null, failure);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome2<T>> results = new ArrayList<>();
            for (Future<Outcome2<T>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------- fixtures

    private void seedTenancy() {
        seedTenant(TENANT, BRAND, "walkin-tenant");
        seedTenant(OTHER_TENANT, OTHER_BRAND, "walkin-other");

        branch = insertLocation("CENTRE", "centre");
        otherBranch = insertLocationFor(OTHER_TENANT, OTHER_BRAND, "WEST", "west");

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'QRTABLE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedTenant(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private UUID insertLocation(String code, String slug) {
        return insertLocationFor(TENANT, BRAND, code, slug);
    }

    private UUID insertLocationFor(UUID tenantId, UUID brandId, String code, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
        return id;
    }

    private void seedFloorPlan() {
        SectionRow hall = transactions.execute(status ->
                floorPlan.createSection(new FloorPlanService.NewSection(TENANT, BRAND, branch, "HALL", "Зал", 0)));
        section = hall.id();
        tableOne = table("T1", 4);
        tableTwo = table("T2", 2);

        SectionRow otherHall = transactions.execute(status -> floorPlan.createSection(
                new FloorPlanService.NewSection(OTHER_TENANT, OTHER_BRAND, otherBranch, "HALL", "Зал", 0)));
        otherTable = transactions
                .execute(status -> floorPlan.createTable(new FloorPlanService.NewTable(
                        OTHER_TENANT, OTHER_BRAND, otherBranch, otherHall.id(), "N1", "N1", 4, false, null, null)))
                .id();
    }

    private UUID table(String code, int seats) {
        TableRow row = transactions.execute(status -> floorPlan.createTable(
                new FloorPlanService.NewTable(TENANT, BRAND, branch, section, code, code, seats, false, null, null)));
        return row.id();
    }

    private UUID seedDineInOrder(String number, long totalMinor, UUID ownerAccount, String status) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DINE_IN', 'UZS',
                        'ACTIVE', :owner, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("owner", ownerAccount)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", branch);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("owner", ownerAccount);
        order.put("total", totalMinor);
        order.put("status", status);
        order.put(
                "confirmedAt",
                List.of("CONFIRMED", "PREPARING", "READY", "FULFILLING", "COMPLETED")
                                .contains(status)
                        ? java.time.OffsetDateTime.now()
                        : null);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'QRTABLE',
                    :owner, 'DINE_IN', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, :status, 'UZS',
                    :total, 0, :total, :quoteId, 'hash', :publicationId, :cartId, :number,
                    1, :confirmedAt)
                """).params(order).update();

        return orderId;
    }

    // ---------------------------------------------------------------- doubles

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ReversibleProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            byte[] reversed =
                    new StringBuilder(plaintext).reverse().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new ProtectedValue("test-key", "TEST", new byte[] {1}, reversed, 1);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            return new StringBuilder(new String(value.ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
                    .reverse()
                    .toString();
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return BearerToken.hash(tenantId + "|" + lookupDomain + "|" + normalizedValue);
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        long count(String actionCode) {
            return facts.stream()
                    .filter(fact -> actionCode.equals(fact.actionCode()))
                    .count();
        }

        AuditFact only(String actionCode) {
            List<AuditFact> matching = facts.stream()
                    .filter(fact -> actionCode.equals(fact.actionCode()))
                    .toList();
            assertThat(matching).as("audit facts " + actionCode).hasSize(1);
            return matching.get(0);
        }
    }
}
