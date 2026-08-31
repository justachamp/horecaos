package uz.horecaos.platform.dinein;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.QrEntryService;
import uz.horecaos.platform.dinein.application.ReservationService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.ordering.JdbcSessionOrderSource;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.ReservationRow;
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
 * Dine-in: the floor plan, the booking hold, the session, and the QR entry
 * (ADR 0047).
 *
 * <p>Against a real PostgreSQL, because the properties that matter here are
 * properties of the database rather than of the Java. Whether two hosts confirming
 * one table for overlapping times both succeed is a question about a GiST
 * exclusion constraint. Whether a booking for four tables that can hold three
 * holds none is a question about one transaction. Whether a table can be occupied
 * twice is a partial unique index. None of those can be asserted against a mock,
 * and every one of them is what actually goes wrong on a Friday.
 *
 * <p>The services are wired by hand and driven through a real
 * {@link TransactionTemplate}, not merely instantiated. Without a transaction
 * manager every statement would autocommit and the all-or-nothing tests below
 * would pass for the wrong reason.
 */
class DineInTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    /** A Friday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-28T07:00:00Z");

    /** Friday 19:30 Tashkent, which is when the interesting bookings are. */
    private static final Instant DINNER = Instant.parse("2026-08-28T14:30:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcDineInStore store;
    private TransactionTemplate transactions;
    private FloorPlanService floorPlan;
    private ReservationService reservations;
    private TableSessionService sessions;
    private QrEntryService qr;
    private RecordingAuditRecorder audit;

    private UUID branch;
    private UUID siblingBranch;
    private UUID channelId;
    private UUID section;
    private UUID tableOne;
    private UUID tableTwo;
    private UUID tableThree;
    private UUID siblingTable;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for dine-in tests");
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

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        store = new JdbcDineInStore(jdbc);
        audit = new RecordingAuditRecorder();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        floorPlan = new FloorPlanService(store, audit, clock);
        reservations = new ReservationService(store, floorPlan, new ReversibleProtection(), audit, clock);
        sessions = new TableSessionService(store, floorPlan, new JdbcSessionOrderSource(jdbc), audit, clock);
        qr = new QrEntryService(store, floorPlan, new InProcessRateLimiter(clock), clock);

        seedTenancy();
        seedFloorPlan();
    }

    // ---------------------------------------------------------------- bookings

    @Test
    @DisplayName("two hosts confirming overlapping times on one table: exactly one wins, and "
            + "the loser sees a conflict rather than a leaked constraint violation")
    void twoConcurrentConfirmationsSettleOnce() throws Exception {
        UUID first = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        UUID second = book(tableOne, DINNER.plus(Duration.ofHours(1)), DINNER.plus(Duration.ofHours(3)));

        // Two real connections on two threads, both holding the confirmation open.
        // The second blocks inside PostgreSQL until the first commits and is then
        // refused — which is the ordering a busy Friday actually produces, and the
        // one no amount of read-then-write can exclude.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = dataSource.getConnection();
                Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            confirmOn(a, first);

            Future<Throwable> loser = pool.submit(() -> catchThrowable(() -> {
                confirmOn(b, second);
                b.commit();
            }));

            // Give the second writer time to reach the constraint and block on it,
            // rather than racing it to the commit.
            Thread.sleep(300);
            a.commit();

            assertThat(loser.get(10, TimeUnit.SECONDS))
                    .as("the second confirmation of an overlapping hold must be refused by the " + "database")
                    .isNotNull();
            b.rollback();
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(first)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(statusOf(second)).isEqualTo(ReservationStatus.REQUESTED);
    }

    @Test
    @DisplayName("the loser of a sequential race gets a stable conflict code")
    void theLoserSeesAStableConflictCode() {
        UUID first = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        UUID second = book(tableOne, DINNER.plus(Duration.ofHours(1)), DINNER.plus(Duration.ofHours(3)));

        confirm(first);

        Throwable failure = catchThrowable(() -> confirm(second));

        assertThat(failure).isInstanceOf(ApiException.class);
        ApiException conflict = (ApiException) failure;
        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(conflict.properties())
                .as("a host standing at a door needs a code a screen can branch on, not a " + "SQLSTATE")
                .containsEntry("conflict", "TABLE_ALREADY_BOOKED");
    }

    @Test
    @DisplayName("a booking for three tables where one is taken holds none of them")
    void aPartlyImpossibleBookingHoldsNothing() {
        confirm(book(tableThree, DINNER, DINNER.plus(Duration.ofHours(2))));

        UUID greedy = book(
                List.of(tableOne, tableTwo, tableThree),
                DINNER.plus(Duration.ofMinutes(30)),
                DINNER.plus(Duration.ofHours(2)));

        assertThat(catchThrowable(() -> confirm(greedy))).isInstanceOf(ApiException.class);

        // The first two tables must be free for somebody else. A partial hold is a
        // table nobody can sell and nobody is sitting at.
        assertThat(heldTables()).as("the whole party is one transaction").containsExactly(tableThree);
    }

    @Test
    @DisplayName("changing the turnaround buffer alters no hold that has already been taken")
    void changingTheTurnaroundBufferMovesNoExistingHold() {
        UUID booking = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        confirm(booking);

        String before = heldRange(booking, tableOne);
        int snapshotted = store.findReservation(TENANT, booking).orElseThrow().turnaroundMinutes();

        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "ORDER_AND_PAY", 120, 240, 0),
                "manager",
                "Longer turnaround for the winter menu"));

        assertThat(heldRange(booking, tableOne))
                .as("a buffer edited in March must not release a table booked in February")
                .isEqualTo(before);
        assertThat(store.findReservation(TENANT, booking).orElseThrow().turnaroundMinutes())
                .isEqualTo(snapshotted);

        // And the new buffer does apply to the next booking, or the setting would
        // be decorative.
        UUID later = book(tableTwo, DINNER, DINNER.plus(Duration.ofHours(2)));
        confirm(later);
        assertThat(store.findReservation(TENANT, later).orElseThrow().turnaroundMinutes())
                .isEqualTo(120);
    }

    @Test
    @DisplayName("cancelling a booking releases its table immediately, not when its hour passes")
    void cancellingReleasesTheHold() {
        UUID booking = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        confirm(booking);
        assertThat(heldTables()).containsExactly(tableOne);

        ReservationRow confirmed = store.findReservation(TENANT, booking).orElseThrow();
        transactions.executeWithoutResult(status -> reservations.move(
                TENANT, booking, ReservationStatus.CANCELLED, confirmed.version(), "host", "Guest rang back"));

        assertThat(heldTables()).isEmpty();

        UUID replacement = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        confirm(replacement);
        assertThat(statusOf(replacement)).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a booking's guest name and phone never appear in an audit change document")
    void personalDataStaysOutOfTheAuditTrail() {
        book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));

        assertThat(audit.facts)
                .allSatisfy(fact -> assertThat(fact.changeDocument().toString())
                        .doesNotContain("Dilnoza")
                        .doesNotContain("998901234567"));
    }

    // ---------------------------------------------------------------- sessions

    @Test
    @DisplayName("a table orders three rounds across an evening and settles once, over a total "
            + "that is the sum of its orders")
    void threeRoundsOneBill() {
        SessionRow session = openWalkIn(tableOne);

        UUID roundOne = seedDineInOrder("D-001", 45_000);
        UUID roundTwo = seedDineInOrder("D-002", 30_000);
        UUID roundThree = seedDineInOrder("D-003", 12_000);

        assertThat(addRound(session.id(), roundOne)).isEqualTo(1);
        assertThat(addRound(session.id(), roundTwo)).isEqualTo(2);
        assertThat(addRound(session.id(), roundThree)).isEqualTo(3);

        assertThat(sessions.bill(TENANT, session.id()).totalMinor())
                .as("87 000 whole som, not 870.00 of anything")
                .isEqualTo(87_000L);

        SessionRow asked = move(session.id(), SessionStatus.BILL_REQUESTED, session.version());
        SessionRow settling = move(session.id(), SessionStatus.SETTLING, asked.version());
        SessionRow closed = move(session.id(), SessionStatus.CLOSED, settling.version());

        assertThat(closed.status()).isEqualTo(SessionStatus.CLOSED);
        assertThat(closed.settledTotalMinor()).isEqualTo(87_000L);
        assertThat(closed.closedAt()).isNotNull();

        // One settlement, not three. Every payment projection for these orders
        // derives from this one act.
        assertThat(audit.facts.stream()
                        .filter(fact -> "dinein.session.closed".equals(fact.actionCode()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a card that declines returns the table to service rather than stranding it")
    void settlingCanReturnToOpen() {
        SessionRow session = openWalkIn(tableOne);
        addRound(session.id(), seedDineInOrder("D-010", 20_000));

        SessionRow asked = move(session.id(), SessionStatus.BILL_REQUESTED, session.version());
        SessionRow settling = move(session.id(), SessionStatus.SETTLING, asked.version());
        SessionRow reopened = move(session.id(), SessionStatus.OPEN, settling.version());

        assertThat(reopened.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(reopened.closedAt()).isNull();

        assertThat(addRound(reopened.id(), seedDineInOrder("D-011", 8_000))).isEqualTo(2);
        assertThat(sessions.bill(TENANT, session.id()).totalMinor()).isEqualTo(28_000L);
    }

    @Test
    @DisplayName("one order cannot be on two bills")
    void anOrderBelongsToOneSessionOnly() {
        SessionRow first = openWalkIn(tableOne);
        SessionRow second = openWalkIn(tableTwo);
        UUID order = seedDineInOrder("D-020", 15_000);

        addRound(first.id(), order);

        Throwable failure = catchThrowable(() -> addRound(second.id(), order));
        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).properties()).containsEntry("conflict", "ORDER_ALREADY_BILLED");
    }

    @Test
    @DisplayName("a table seats one party at a time")
    void oneLivePartyPerTable() {
        openWalkIn(tableOne);

        Throwable failure = catchThrowable(() -> openWalkIn(tableOne));
        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).properties()).containsEntry("conflict", "TABLE_OCCUPIED");
    }

    @Test
    @DisplayName("closing a session frees its tables, so the next party can be seated")
    void closingFreesTheTable() {
        SessionRow session = openWalkIn(tableOne);
        SessionRow closed = move(session.id(), SessionStatus.CLOSED, session.version());

        assertThat(closed.status()).isEqualTo(SessionStatus.CLOSED);
        assertThat(openWalkIn(tableOne).status()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    @DisplayName("a walkout is force-closed with a reason code and the unsettled amount on the " + "audit record")
    void aWalkoutIsAttributable() {
        SessionRow session = openWalkIn(tableOne);
        addRound(session.id(), seedDineInOrder("D-030", 61_000));

        SessionRow forced = transactions.execute(status -> sessions.move(
                TENANT,
                session.id(),
                SessionStatus.FORCE_CLOSED,
                session.version(),
                "WALKOUT",
                "duty-manager",
                "Party left without paying"));

        assertThat(forced.status()).isEqualTo(SessionStatus.FORCE_CLOSED);
        assertThat(forced.closeReasonCode()).isEqualTo("WALKOUT");

        AuditFact record = audit.facts.stream()
                .filter(fact -> "dinein.session.force-closed".equals(fact.actionCode()))
                .findFirst()
                .orElseThrow();

        assertThat(record.capabilityUsed()).isEqualTo("dinein.session.force_close");
        assertThat(record.changeDocument())
                .as("a shift's cash shortfall has to be attributable to a person and an amount")
                .containsEntry("unsettledMinor", 61_000L)
                .containsEntry("closeReasonCode", "WALKOUT");
    }

    @Test
    @DisplayName("a force-close with no reason code is refused")
    void aForceCloseNeedsAReasonCode() {
        SessionRow session = openWalkIn(tableOne);

        Throwable failure = catchThrowable(() -> transactions.execute(status -> sessions.move(
                TENANT,
                session.id(),
                SessionStatus.FORCE_CLOSED,
                session.version(),
                null,
                "duty-manager",
                "no reason given")));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("seating a booking makes it an occupancy, and the booking cannot be seated twice")
    void seatingIsAnOccupancy() {
        UUID booking = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        confirm(booking);

        SessionRow seated = transactions.execute(status -> sessions.open(
                new TableSessionService.OpenSession(
                        TENANT, BRAND, branch, booking, List.of(tableOne), 4, "UZS", "host"),
                "Party arrived"));

        assertThat(statusOf(booking)).isEqualTo(ReservationStatus.SEATED);
        assertThat(seated.reservationId()).isEqualTo(booking);

        Throwable failure = catchThrowable(() -> transactions.execute(status -> sessions.open(
                new TableSessionService.OpenSession(
                        TENANT, BRAND, branch, booking, List.of(tableTwo), 4, "UZS", "host"),
                "Seated again by mistake")));

        assertThat(failure).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a delivery order is not a round of a dine-in bill")
    void onlyDineInOrdersJoinASession() {
        SessionRow session = openWalkIn(tableOne);
        UUID delivery = seedOrder("D-040", 20_000, "DELIVERY", branch);

        assertThat(catchThrowable(() -> addRound(session.id(), delivery))).isInstanceOf(ApiException.class);
    }

    // -------------------------------------------------------------- the QR code

    @Test
    @DisplayName("a scan is exchanged for a guest token, and the printed token is stored only " + "as a digest")
    void scanningExchangesForAGuestToken() {
        String printed = issueToken(tableOne);
        enableOrdering();

        QrEntryService.GuestAdmission admission = transactions.execute(status -> qr.exchange(printed));

        assertThat(admission.guestToken()).isNotBlank();
        assertThat(admission.tableId()).isEqualTo(tableOne);
        assertThat(admission.mode()).isEqualTo(QrMode.ORDER_AND_PAY);

        assertThat(jdbc.sql("SELECT qr_token_hash FROM dinein.tables WHERE id = :id")
                        .param("id", tableOne)
                        .query(String.class)
                        .single())
                .as("the token itself is never written anywhere")
                .isEqualTo(BearerToken.hash(printed))
                .isNotEqualTo(printed);

        QrEntryService.GuestContext resolved = qr.resolve(admission.guestToken());
        assertThat(resolved.tableId()).isEqualTo(tableOne);
        assertThat(resolved.tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("a rotated token stops working immediately and the old one cannot be replayed")
    void rotationIsImmediate() {
        String printed = issueToken(tableOne);
        enableOrdering();

        QrEntryService.GuestAdmission admission = transactions.execute(status -> qr.exchange(printed));
        assertThat(qr.resolve(admission.guestToken()).tableId()).isEqualTo(tableOne);

        int version = store.findTable(TENANT, tableOne).orElseThrow().version();
        FloorPlanService.IssuedQrToken rotated = transactions.execute(
                status -> floorPlan.rotateQrToken(TENANT, tableOne, version, "manager", "Code photographed"));

        assertThat(rotated.revokedGuestSessions())
                .as("rotation without revocation leaves the photographed code working for "
                        + "whatever remained of its guests' four hours")
                .isEqualTo(1);

        assertThat(catchThrowable(() -> transactions.execute(status -> qr.exchange(printed))))
                .as("the old printed code is dead the moment the new one is written")
                .isInstanceOf(ApiException.class);
        assertThat(catchThrowable(() -> qr.resolve(admission.guestToken())))
                .as("and so is every guest token minted from it")
                .isInstanceOf(ApiException.class);

        // The new code works, or rotation would be a denial of service.
        assertThat(transactions
                        .execute(status -> qr.exchange(rotated.plaintext()))
                        .tableId())
                .isEqualTo(tableOne);
    }

    @Test
    @DisplayName("an unknown, an archived and an unconfigured table all refuse identically")
    void everyRefusalLooksTheSame() {
        String printed = issueToken(tableOne);
        enableOrdering();

        ApiException unknown =
                (ApiException) catchThrowable(() -> transactions.execute(status -> qr.exchange("not-a-real-token")));

        int version = store.findTable(TENANT, tableOne).orElseThrow().version();
        transactions.executeWithoutResult(status ->
                floorPlan.changeTableStatus(TENANT, tableOne, version, "ARCHIVED", "manager", "Table removed"));

        ApiException archived =
                (ApiException) catchThrowable(() -> transactions.execute(status -> qr.exchange(printed)));

        assertThat(unknown.errorCode()).isEqualTo(archived.errorCode());
        assertThat(unknown.getMessage())
                .as("distinguishing them tells a caller whether a guessed value was ever valid")
                .isEqualTo(archived.getMessage());
    }

    @Test
    @DisplayName("a guest token reaches only its own table's bill")
    void aGuestCannotReachTheNextTablesBill() {
        String printed = issueToken(tableOne);
        enableOrdering();

        SessionRow mine = openWalkIn(tableOne);
        SessionRow theirs = openWalkIn(tableTwo);

        QrEntryService.GuestContext guest =
                qr.resolve(transactions.execute(status -> qr.exchange(printed)).guestToken());

        assertThat(qr.requireSessionAtTable(guest, mine.id()).id()).isEqualTo(mine.id());
        assertThat(catchThrowable(() -> qr.requireSessionAtTable(guest, theirs.id())))
                .as("the session is checked against the table the token was minted for, not " + "merely parsed")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a VIEW_ONLY branch mints a token that can order nothing")
    void viewOnlyOrdersNothing() {
        String printed = issueToken(tableOne);
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "VIEW_ONLY", null, null, null),
                "manager",
                "Menu only for now"));

        QrEntryService.GuestAdmission admission = transactions.execute(status -> qr.exchange(printed));

        assertThat(admission.mode()).isEqualTo(QrMode.VIEW_ONLY);
        assertThat(admission.openSessionId())
                .as("HorecaOS creates nothing in VIEW_ONLY, so there is no bill to point at")
                .isNull();
    }

    @Test
    @DisplayName("SETTLE_OPEN_TICKET is refused at configuration time, and by the database")
    void settleOpenTicketIsRefusedUntilAnAdapterExists() {
        Throwable atConfiguration =
                catchThrowable(() -> transactions.executeWithoutResult(status -> floorPlan.configure(
                        new FloorPlanService.BranchSettings(
                                TENANT, BRAND, branch, "SETTLE_OPEN_TICKET", null, null, null),
                        "manager",
                        "Trying the POS mode")));

        assertThat(atConfiguration)
                .as("ADR 0011 forbids an unsupported capability being the sole business path, "
                        + "and no adapter declares either new port")
                .isInstanceOf(ApiException.class);

        Throwable atTheDatabase = catchThrowable(() -> jdbc.sql("""
                INSERT INTO dinein.location_settings (tenant_id, brand_id, location_id, qr_mode)
                VALUES (:tenantId, :brandId, :locationId, 'SETTLE_OPEN_TICKET')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .update());

        assertThat(atTheDatabase)
                .as("a CHECK as well as a service, so a hand-written row cannot enable a mode "
                        + "with no adapter behind it")
                .isNotNull();
    }

    // ------------------------------------------------------------ tenancy walls

    @Test
    @DisplayName("cross-tenant reads of sections, tables, bookings and sessions all fail")
    void crossTenantReadsFail() {
        UUID booking = book(tableOne, DINNER, DINNER.plus(Duration.ofHours(2)));
        SessionRow session = openWalkIn(tableTwo);

        assertThat(store.listSections(OTHER_TENANT, branch)).isEmpty();
        assertThat(store.listTables(OTHER_TENANT, branch)).isEmpty();
        assertThat(store.findTable(OTHER_TENANT, tableOne)).isEmpty();
        assertThat(store.findReservation(OTHER_TENANT, booking)).isEmpty();
        assertThat(store.findSession(OTHER_TENANT, session.id())).isEmpty();
        assertThat(store.listLiveSessions(OTHER_TENANT, branch)).isEmpty();
        assertThat(store.tableAvailability(OTHER_TENANT, branch, DINNER, DINNER.plus(Duration.ofHours(2))))
                .isEmpty();
    }

    @Test
    @DisplayName("a booking cannot hold a table at another branch")
    void aBookingStaysAtItsOwnBranch() {
        Throwable failure = catchThrowable(() ->
                transactions.execute(status -> reservations.request(new ReservationService.NewReservation(
                        TENANT,
                        BRAND,
                        branch,
                        null,
                        "Dilnoza",
                        "998901234567",
                        null,
                        null,
                        4,
                        DINNER,
                        DINNER.plus(Duration.ofHours(2)),
                        List.of(siblingTable),
                        channelId,
                        "host"))));

        assertThat(failure).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an order placed at another branch is not a round of this table's bill")
    void aRoundStaysAtItsOwnBranch() {
        SessionRow session = openWalkIn(tableOne);
        UUID elsewhere = seedOrder("D-050", 20_000, "DINE_IN", siblingBranch);

        assertThat(catchThrowable(() -> addRound(session.id(), elsewhere))).isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ helpers

    private void enableOrdering() {
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, branch, "ORDER_AND_PAY", null, null, null),
                "manager",
                "QR ordering on"));
    }

    private String issueToken(UUID tableId) {
        int version = store.findTable(TENANT, tableId).orElseThrow().version();
        return transactions
                .execute(status -> floorPlan.rotateQrToken(TENANT, tableId, version, "manager", "First printing"))
                .plaintext();
    }

    private UUID book(UUID tableId, Instant from, Instant to) {
        return book(List.of(tableId), from, to);
    }

    private UUID book(List<UUID> tableIds, Instant from, Instant to) {
        return transactions
                .execute(status -> reservations.request(new ReservationService.NewReservation(
                        TENANT,
                        BRAND,
                        branch,
                        null,
                        "Dilnoza",
                        "998901234567",
                        null,
                        "Window if possible",
                        4,
                        from,
                        to,
                        tableIds,
                        channelId,
                        "host")))
                .id();
    }

    private void confirm(UUID reservationId) {
        ReservationRow row = store.findReservation(TENANT, reservationId).orElseThrow();
        transactions.executeWithoutResult(status -> reservations.move(
                TENANT, reservationId, ReservationStatus.CONFIRMED, row.version(), "host", "Table available"));
    }

    /** Confirms on one specific connection, so two can be raced against each other. */
    private void confirmOn(Connection connection, UUID reservationId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE dinein.reservations SET status = 'CONFIRMED', version = version + 1 "
                    + "WHERE id = '" + reservationId + "'");
        }
    }

    private ReservationStatus statusOf(UUID reservationId) {
        return store.findReservation(TENANT, reservationId).orElseThrow().status();
    }

    private List<UUID> heldTables() {
        return jdbc.sql("""
                SELECT table_id FROM dinein.reservation_tables
                WHERE status IN ('CONFIRMED', 'SEATED') ORDER BY table_id
                """).query(UUID.class).list();
    }

    private String heldRange(UUID reservationId, UUID tableId) {
        return jdbc.sql("""
                SELECT held_during::text FROM dinein.reservation_tables
                WHERE reservation_id = :reservationId AND table_id = :tableId
                """)
                .param("reservationId", reservationId)
                .param("tableId", tableId)
                .query(String.class)
                .single();
    }

    private SessionRow openWalkIn(UUID tableId) {
        return transactions.execute(status -> sessions.open(
                new TableSessionService.OpenSession(TENANT, BRAND, branch, null, List.of(tableId), 2, "UZS", "waiter"),
                "Walk-in"));
    }

    private int addRound(UUID sessionId, UUID orderId) {
        return transactions.execute(status -> sessions.addRound(TENANT, sessionId, orderId, "waiter", "Round fired"));
    }

    private SessionRow move(UUID sessionId, SessionStatus to, int expectedVersion) {
        return transactions.execute(
                status -> sessions.move(TENANT, sessionId, to, expectedVersion, null, "waiter", "Service"));
    }

    // -------------------------------------------------------------- fixtures

    private UUID publicationId;

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'dinein-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = insertLocation("CENTRE", "centre");
        siblingBranch = insertLocation("NORTH", "north");

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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'QRTABLE', 'PUBLISHED', 'hash',
                        now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private UUID insertLocation(String code, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
        return id;
    }

    private void seedFloorPlan() {
        SectionRow hall = transactions.execute(status ->
                floorPlan.createSection(new FloorPlanService.NewSection(TENANT, BRAND, branch, "HALL", "Зал", 0)));
        section = hall.id();

        tableOne = table(branch, section, "T1", 4);
        tableTwo = table(branch, section, "T2", 2);
        tableThree = table(branch, section, "T3", 6);

        SectionRow siblingSection = transactions.execute(status -> floorPlan.createSection(
                new FloorPlanService.NewSection(TENANT, BRAND, siblingBranch, "HALL", "Зал", 0)));
        siblingTable = table(siblingBranch, siblingSection.id(), "N1", 4);
    }

    private UUID table(UUID locationId, UUID sectionId, String code, int seats) {
        TableRow row = transactions.execute(status -> floorPlan.createTable(new FloorPlanService.NewTable(
                TENANT, BRAND, locationId, sectionId, code, code, seats, false, null, null)));
        return row.id();
    }

    private UUID seedDineInOrder(String number, long totalMinor) {
        return seedOrder(number, totalMinor, "DINE_IN", branch);
    }

    private UUID seedOrder(String number, long totalMinor, String mode, UUID locationId) {
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
                .param("locationId", locationId)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :mode, 'UZS',
                        'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("mode", mode)
                .param("guest", "guest-" + number)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", locationId);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("guest", "guest-" + number);
        order.put("mode", mode);
        order.put("total", totalMinor);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'QRTABLE',
                    :guest, :mode, 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS',
                    :total, 0, :total, :quoteId, 'hash', :publicationId, :cartId, :guest,
                    1, now())
                """).params(order).update();

        return orderId;
    }

    // ---------------------------------------------------------------- doubles

    /**
     * Reversible rather than real encryption, so a test can assert that a value
     * was protected without standing up ADR 0029 key management. It is deliberately
     * not a no-op: a pass-through would let a plaintext leak into a column and the
     * assertions above would not notice.
     */
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
    }
}
