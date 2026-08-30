package uz.qoida.platform.kitchen;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.fulfillment.api.OrderProgressPort;
import uz.qoida.platform.inventory.api.InventoryReservationPort;
import uz.qoida.platform.inventory.api.ReservationResult;
import uz.qoida.platform.kitchen.application.KitchenStationService;
import uz.qoida.platform.kitchen.application.KitchenTicketService;
import uz.qoida.platform.kitchen.domain.KitchenStateMachine;
import uz.qoida.platform.kitchen.domain.ReleaseMode;
import uz.qoida.platform.kitchen.domain.RoutingLevel;
import uz.qoida.platform.kitchen.domain.StationRole;
import uz.qoida.platform.kitchen.domain.TicketItemStatus;
import uz.qoida.platform.kitchen.domain.TicketStatus;
import uz.qoida.platform.kitchen.infrastructure.ordering.JdbcKitchenOrderSource;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.qoida.platform.ordering.api.OrderSettlementPort;
import uz.qoida.platform.ordering.application.OrderAcceptancePolicyService;
import uz.qoida.platform.ordering.application.OrderInventoryProcess;
import uz.qoida.platform.ordering.application.OrderProgressAdapter;
import uz.qoida.platform.ordering.application.OrderStateService;
import uz.qoida.platform.ordering.domain.OrderStatus;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.tenancy.api.LocationCapacityPort;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;
import uz.qoida.platform.web.api.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Kitchen execution, production routing, and kitchen release (ADR 0041).
 *
 * <p>Against a real PostgreSQL, because almost every property under test is a
 * property of the database rather than of the Java. Whether two devices marking
 * one item ready settle once is a question about a conditional UPDATE's row
 * count; whether an item can name a station at another branch is a question about
 * a composite foreign key; whether a branch can have two grills is a partial
 * unique index. None of those can be tested against a mock, and every one of them
 * is the thing that goes wrong in a real service.
 */
class KitchenExecutionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    /** A Tuesday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcKitchenStore store;
    private KitchenStationService stationService;
    private KitchenTicketService tickets;
    private RecordingOrderProgressPort proposals;
    private RecordingAuditRecorder audit;

    /**
     * The same kitchen with ordering's real adapter behind the port (ADR 0041
     * rollout step 2).
     *
     * <p>A second service rather than a replacement, because the two answer
     * different questions. {@link #tickets} asks what the kitchen proposes and is
     * indifferent to what ordering does with it; this one asks whether the order
     * actually moved, and only a real {@code OrderStateService} over a real
     * {@code ordering.orders} can answer that. A recording port cannot: it would
     * be the test agreeing with itself about a transition ADR 0019 owns.
     */
    private KitchenTicketService wiredTickets;
    private JdbcOrderStore orderStore;
    private RecordingSettlements settlements;
    private List<UUID> capacityReleases;

    private UUID branch;
    private UUID siblingBranch;
    private UUID catalogId;
    private UUID publicationId;
    private UUID channelId;

    private UUID grillStation;
    private UUID coldStation;
    private UUID fallbackStation;

    private Catalogue burger;
    private Catalogue salad;
    private Catalogue tea;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for kitchen execution tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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

        jdbc.sql("TRUNCATE TABLE kitchen.ticket_events, kitchen.ticket_items, kitchen.tickets, "
                + "kitchen.location_routing_rules, kitchen.brand_routing_rules, "
                + "kitchen.stations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.order_revisions, "
                + "ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                + "catalog.variants, catalog.products, catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        store = new JdbcKitchenStore(jdbc);
        proposals = new RecordingOrderProgressPort();
        audit = new RecordingAuditRecorder();
        stationService = new KitchenStationService(store, clock);
        tickets = new KitchenTicketService(store, new JdbcKitchenOrderSource(jdbc), proposals,
                audit, clock);

        ObjectMapper objectMapper = JsonMapper.builder().build();
        orderStore = new JdbcOrderStore(jdbc);
        settlements = new RecordingSettlements();
        capacityReleases = new CopyOnWriteArrayList<>();
        // The real ordering service, over the real orders table. The two
        // collaborators a kitchen proposal cannot reach are recorded rather than
        // stubbed into silence: releasing a slot and settling a handover are the
        // consequences of a COMPLETED proposal, and a test that could not see
        // them would pass while ADR 0036's ceiling stayed held for ever.
        var orderState = new OrderStateService(orderStore, new RecordingCapacity(),
                new OrderInventoryProcess(new JdbcOrderProcessStore(jdbc), REFUSES_INVENTORY,
                        objectMapper, clock),
                new OrderAcceptancePolicyService(new JdbcPolicyResolver(jdbc, objectMapper)),
                settlements, audit, event -> { }, clock);
        wiredTickets = new KitchenTicketService(store, new JdbcKitchenOrderSource(jdbc),
                new OrderProgressAdapter(orderState), audit, clock);

        seedTenancy();
        seedCatalogue();
        seedStations();
    }

    // ------------------------------------------------------------------- routing

    @Test
    @DisplayName("routing resolves through all five levels, most specific first")
    void routingResolvesThroughAllFiveLevels() {
        // Level 5: nothing is mapped at all, so the fallback takes it and says so.
        assertThat(resolve(burger)).isEqualTo(new Resolution(fallbackStation, RoutingLevel.FALLBACK));

        // Level 4: the brand maps the category to a role, and this branch's station
        // carrying that role answers for it.
        brandRule(null, null, burger.categoryId(), StationRole.GRILL);
        assertThat(resolve(burger)).isEqualTo(new Resolution(grillStation, RoutingLevel.BRAND_ROLE));

        // Level 4 again, on the product: still the brand layer, and it beats the
        // brand's category rule because the query orders variant before product
        // before category inside the layer.
        brandRule(null, burger.productId(), null, StationRole.COLD);
        assertThat(resolve(burger)).isEqualTo(new Resolution(coldStation, RoutingLevel.BRAND_ROLE));

        // Level 3: any location override outranks every brand rule.
        locationRule(null, null, burger.categoryId(), grillStation);
        assertThat(resolve(burger))
                .isEqualTo(new Resolution(grillStation, RoutingLevel.LOCATION_CATEGORY));

        // Level 2.
        locationRule(null, burger.productId(), null, coldStation);
        assertThat(resolve(burger))
                .isEqualTo(new Resolution(coldStation, RoutingLevel.LOCATION_PRODUCT));

        // Level 1. The narrowest rule at the narrowest scope wins outright.
        locationRule(burger.variantId(), null, null, grillStation);
        assertThat(resolve(burger))
                .isEqualTo(new Resolution(grillStation, RoutingLevel.LOCATION_VARIANT));
    }

    @Test
    @DisplayName("an unmapped variant lands on the fallback station and raises the event")
    void anUnmappedVariantLandsOnTheFallbackAndSaysSo() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-014", null, null, null, burger, tea);

        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        List<TicketItemRow> items = store.itemsOf(TENANT, ticket.id());

        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(item -> {
            assertThat(item.stationId()).isEqualTo(grillStation);
            assertThat(item.routedBy()).isEqualTo(RoutingLevel.BRAND_ROLE);
        });
        // The tea matched nothing. It is on a screen — the fallback — rather than
        // nowhere, which is the whole point: a line on no screen is a dish nobody
        // cooks and a customer who waits for it.
        assertThat(items).anySatisfy(item -> {
            assertThat(item.stationId()).isEqualTo(fallbackStation);
            assertThat(item.routedBy()).isEqualTo(RoutingLevel.FALLBACK);
        });

        assertThat(store.eventsOf(TENANT, ticket.id()))
                .as("the unresolved line has to be findable, or the fallback silently "
                        + "accumulates an unmapped menu")
                .anyMatch(event -> "ROUTING_UNRESOLVED".equals(event.trigger()));
    }

    @Test
    @DisplayName("an override pointing at an archived station falls through rather than "
            + "resolving to a screen nobody watches")
    void anArchivedStationDoesNotWinTheResolution() {
        brandRule(null, burger.productId(), null, StationRole.COLD);
        locationRule(burger.variantId(), null, null, grillStation);
        assertThat(resolve(burger).stationId()).isEqualTo(grillStation);

        jdbc.sql("UPDATE kitchen.stations SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", grillStation).update();

        assertThat(resolve(burger))
                .as("an archived station is indistinguishable from losing the dish")
                .isEqualTo(new Resolution(coldStation, RoutingLevel.BRAND_ROLE));
    }

    @Test
    @DisplayName("a branch cannot have two active stations carrying one role")
    void oneActiveStationPerRole() {
        Throwable failure = catchThrowable(() -> stationService.create(
                new KitchenStationService.NewStation(TENANT, BRAND, branch, "GRILL2",
                        StationRole.GRILL, "Гриль 2", "Gril 2", "Grill 2", 9, false)));

        assertThat(failure)
                .as("a brand rule resolves a role to \"the location's station carrying it\", "
                        + "and with two that question has no answer")
                .isInstanceOf(ApiException.class);
    }

    // --------------------------------------------------------- tickets and roll-up

    @Test
    @DisplayName("three stations finishing in the same second propose exactly one order READY")
    void threeStationsProposeExactlyOneOrderReady() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        brandRule(null, salad.productId(), null, StationRole.COLD);
        UUID orderId = seedConfirmedOrder("A-021", null, null, null, burger, salad, tea);

        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        assertThat(ticket.status()).isEqualTo(TicketStatus.FIRED);

        List<TicketItemRow> items = store.itemsOf(TENANT, ticket.id());
        items.forEach(item -> tickets.start(TENANT, item.id(), "cook", null));
        assertThat(tickets.require(TENANT, ticket.id()).status())
                .isEqualTo(TicketStatus.IN_PRODUCTION);
        assertThat(proposals.of(orderId)).containsExactly(OrderProgressPort.OrderProgress.PREPARING);

        items.forEach(item -> tickets.ready(TENANT, item.id(), "cook", null));

        assertThat(tickets.require(TENANT, ticket.id()).status()).isEqualTo(TicketStatus.READY);
        assertThat(proposals.of(orderId))
                .as("the roll-up is a function of the item set, so only the update that "
                        + "actually moved the ticket proposes anything")
                .containsExactly(OrderProgressPort.OrderProgress.PREPARING,
                        OrderProgressPort.OrderProgress.READY);
    }

    @Test
    @DisplayName("two devices marking one item ready settle once, and the loser sees the "
            + "settled state rather than an error")
    void twoDevicesSettleOnce() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-022", null, null, null, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        tickets.start(TENANT, item.id(), "cook-a", null);
        var first = tickets.ready(TENANT, item.id(), "cook-a", null);
        var second = tickets.ready(TENANT, item.id(), "cook-b", null);

        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(second.item().status())
                .as("a cook cannot interpret an error, and a screen that errors on a second "
                        + "tap gets tapped a third time")
                .isEqualTo(TicketItemStatus.READY);
        assertThat(proposals.of(orderId))
                .containsExactly(OrderProgressPort.OrderProgress.PREPARING,
                        OrderProgressPort.OrderProgress.READY);
    }

    @Test
    @DisplayName("an offline client replaying twelve queued advances produces twelve "
            + "transitions, not twenty-four")
    void replayedAdvancesSettleOnce() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        brandRule(null, salad.productId(), null, StationRole.COLD);
        UUID orderId = seedConfirmedOrder("A-023", null, null, null, burger, salad, tea);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        List<TicketItemRow> items = store.itemsOf(TENANT, ticket.id());

        List<Runnable> queued = new ArrayList<>();
        for (TicketItemRow item : items) {
            queued.add(() -> tickets.start(TENANT, item.id(), "offline", null));
            queued.add(() -> tickets.ready(TENANT, item.id(), "offline", null));
        }
        queued.forEach(Runnable::run);
        queued.forEach(Runnable::run);

        long stationEvents = store.eventsOf(TENANT, ticket.id()).stream()
                .filter(event -> "STATION_ACTION".equals(event.trigger()))
                .count();
        assertThat(stationEvents)
                .as("a blind retry must settle, not double")
                .isEqualTo(items.size() * 2L);
    }

    // -------------------------------------------------------------------- recall

    @Test
    @DisplayName("a recall before handover succeeds and never moves the order backwards")
    void aRecallBeforeHandoverDoesNotTouchTheOrder() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-030", null, null, null, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        tickets.start(TENANT, item.id(), "cook", null);
        tickets.ready(TENANT, item.id(), "cook", null);
        assertThat(tickets.require(TENANT, ticket.id()).status()).isEqualTo(TicketStatus.READY);

        var recalled = tickets.recall(TENANT, item.id(), "WRONG_TICKET", "expo", null);

        assertThat(recalled.applied()).isTrue();
        TicketRow after = tickets.require(TENANT, ticket.id());
        assertThat(after.status()).isEqualTo(TicketStatus.IN_PRODUCTION);
        assertThat(after.readyAt())
                .as("a ticket back on the grill must not still claim an instant at which it "
                        + "was ready; every lateness figure derives from it")
                .isNull();
        assertThat(proposals.of(orderId))
                .as("ADR 0041: a recall never moves the order backwards, so nothing is even "
                        + "proposed")
                .containsExactly(OrderProgressPort.OrderProgress.PREPARING,
                        OrderProgressPort.OrderProgress.READY);
    }

    @Test
    @DisplayName("a recall after handover is refused, recorded, and audited")
    void aRecallAfterHandoverIsRefused() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-031", null, null, null, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        tickets.start(TENANT, item.id(), "cook", null);
        tickets.ready(TENANT, item.id(), "cook", null);
        // Handover itself is not built in this slice, so the terminal state is
        // reached directly. The rule under test is about the state, not the route
        // to it.
        store.transitionTicket(TENANT, ticket.id(), TicketStatus.READY, TicketStatus.HANDED_OVER,
                NOON);

        Throwable failure = catchThrowable(
                () -> tickets.recall(TENANT, item.id(), "WRONG_TICKET", "expo", null));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).properties())
                .containsEntry("exceptionCode", "KitchenRecallAfterReady");
        assertThat(store.eventsOf(TENANT, ticket.id()))
                .as("somebody tried to recall food that had already left; that is exactly "
                        + "what an operational exception is about")
                .anyMatch(event -> "KITCHEN_RECALL_AFTER_READY".equals(event.reasonCode()));
        assertThat(audit.facts)
                .anyMatch(fact -> "kitchen.ticket.recall".equals(fact.actionCode())
                        && fact.outcome() == AuditFact.Outcome.REJECTED);
    }

    // ------------------------------------------------------------------- release

    @Test
    @DisplayName("a preorder is held and fires from the promise, not from the confirmation")
    void aPreorderIsHeldUntilItsOwnInstant() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        // Promised nine hours out, twenty-five minutes in the kitchen, twenty on
        // the road: due at the pass at 20:40 and fired at 20:15.
        Instant promisedAt = NOON.plus(Duration.ofHours(9));
        UUID orderId = seedConfirmedOrder("A-040", promisedAt, 25, 20, burger);

        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        assertThat(ticket.status())
                .as("without a held state the food is cooked at 12:00 and thrown away")
                .isEqualTo(TicketStatus.HELD);
        assertThat(ticket.releaseMode()).isEqualTo(ReleaseMode.SCHEDULED);
        assertThat(ticket.targetReadyAt()).isEqualTo(promisedAt.minus(Duration.ofMinutes(20)));
        assertThat(ticket.releaseAt())
                .isEqualTo(promisedAt.minus(Duration.ofMinutes(45)));
        assertThat(proposals.of(orderId)).isEmpty();

        // Nothing is due at noon.
        assertThat(tickets.releaseDue(50)).isZero();
    }

    @Test
    @DisplayName("the scheduler fires a ticket whose instant has passed, once")
    void theSchedulerFiresDueTickets() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-041", NOON.plus(Duration.ofHours(9)), 25, 20, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        KitchenTicketService later = new KitchenTicketService(store,
                new JdbcKitchenOrderSource(jdbc), proposals, audit,
                Clock.fixed(ticket.releaseAt().plusSeconds(1), ZoneOffset.UTC));

        assertThat(later.releaseDue(50)).isEqualTo(1);
        assertThat(later.releaseDue(50))
                .as("a second sweep must not re-fire what it already fired")
                .isZero();
        assertThat(tickets.require(TENANT, ticket.id()).status()).isEqualTo(TicketStatus.FIRED);
    }

    @Test
    @DisplayName("an order with no promise fires immediately rather than waiting for a "
            + "number nobody set")
    void anUnpromisedOrderFiresImmediately() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-042", null, null, null, burger);

        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        assertThat(ticket.status()).isEqualTo(TicketStatus.FIRED);
        assertThat(ticket.releaseMode()).isEqualTo(ReleaseMode.AUTO_ON_CONFIRM);
        assertThat(ticket.targetReadyAt()).isNull();
    }

    @Test
    @DisplayName("firing later than the promise permits needs the override capability, a "
            + "reason, and an audit fact")
    void firingLateIsBoundedAndAudited() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        Instant promisedAt = NOON.plus(Duration.ofHours(9));
        UUID orderId = seedConfirmedOrder("A-043", promisedAt, 25, 20, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        Instant tooLate = ticket.releaseAt().plus(Duration.ofMinutes(30));

        Throwable refused = catchThrowable(() -> tickets.reschedule(TENANT, ticket.id(),
                ticket.version(), ReleaseMode.SCHEDULED, tooLate, false, "RUSH", "manager", null));
        assertThat(refused).isInstanceOf(ApiException.class);

        Throwable noReason = catchThrowable(() -> tickets.reschedule(TENANT, ticket.id(),
                ticket.version(), ReleaseMode.SCHEDULED, tooLate, true, null, "manager", null));
        assertThat(noReason).isInstanceOf(ApiException.class);

        TicketRow after = tickets.reschedule(TENANT, ticket.id(), ticket.version(),
                ReleaseMode.SCHEDULED, tooLate, true, "RUSH", "manager", null);

        assertThat(after.releaseAt()).isEqualTo(tooLate);
        assertThat(audit.facts)
                .as("a kitchen that quietly holds a ticket to protect its own throughput "
                        + "number produces a late order nobody was warned about")
                .anyMatch(fact -> "kitchen.ticket.release-override".equals(fact.actionCode()));
    }

    @Test
    @DisplayName("pulling a fire time earlier needs no override")
    void firingEarlierNeedsNothing() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-044", NOON.plus(Duration.ofHours(9)), 25, 20, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        Instant earlier = ticket.releaseAt().minus(Duration.ofMinutes(30));
        TicketRow after = tickets.reschedule(TENANT, ticket.id(), ticket.version(),
                ReleaseMode.SCHEDULED, earlier, false, null, "manager", null);

        assertThat(after.releaseAt()).isEqualTo(earlier);
        assertThat(audit.facts).isEmpty();
    }

    @Test
    @DisplayName("cooking from a ticket still in the buffer is refused")
    void aHeldTicketCannotBeCookedFrom() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-045", NOON.plus(Duration.ofHours(9)), 25, 20, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        assertThat(catchThrowable(() -> tickets.start(TENANT, item.id(), "cook", null)))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("a ticket item cannot name a station at a sibling location, at SQL")
    void anItemCannotReachASiblingBranchesStation() {
        StationRow siblingGrill = stationService.create(new KitchenStationService.NewStation(
                TENANT, BRAND, siblingBranch, "GRILL", StationRole.GRILL, "Гриль", "Gril", "Grill",
                1, true));

        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-050", null, null, null, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        Throwable failure = catchThrowable(() -> jdbc.sql("""
                UPDATE kitchen.ticket_items SET station_id = :station
                WHERE ticket_id = :ticket
                """).param("station", siblingGrill.id()).param("ticket", ticket.id()).update());

        assertThat(failure)
                .as("the composite key binds a ticket item's station to its ticket's branch, so "
                        + "no application bug can put one branch's dish on another's screen")
                .isNotNull();
    }

    @Test
    @DisplayName("a ticket lookup that names the wrong tenant answers nothing")
    void aTicketIsInvisibleToAnotherTenant() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-051", null, null, null, burger);
        TicketRow ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        assertThat(store.findTicket(UUID.randomUUID(), ticket.id())).isEmpty();
    }

    @Test
    @DisplayName("one order gets one ticket however many times its confirmation arrives")
    void aConfirmationReplayedProducesOneTicket() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-052", null, null, null, burger);

        TicketRow first = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketRow again = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.itemsOf(TENANT, first.id())).hasSize(1);
    }

    @Test
    @DisplayName("a branch with no fallback station cannot open a ticket")
    void aBranchWithoutAFallbackRefusesTickets() {
        jdbc.sql("UPDATE kitchen.stations SET is_fallback = false WHERE location_id = :id")
                .param("id", branch).update();
        UUID orderId = seedConfirmedOrder("A-053", null, null, null, burger);

        assertThat(catchThrowable(
                () -> tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM)))
                .as("a branch with nowhere to put an unmapped dish must not run a screen")
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------ proposals that reach ordering

    @Test
    @DisplayName("a ticket advancing moves the order, through ADR 0019's machine")
    void aTicketAdvancingMovesTheOrder() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-060", null, null, null, burger);
        TicketRow ticket = wiredTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);

        wiredTickets.start(TENANT, item.id(), "cook", null);
        assertThat(statusOf(orderId))
                .as("the first item started is what ADR 0041 says PREPARING means")
                .isEqualTo(OrderStatus.PREPARING);

        wiredTickets.ready(TENANT, item.id(), "cook", null);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.READY);

        assertThat(kitchenHistoryOf(orderId))
                .as("recorded as the kitchen's own transitions, not as an operator's, so a "
                        + "rollback of the pilot can tell which orders the screen drove")
                .containsExactly("CONFIRMED->PREPARING", "PREPARING->READY");
        assertThat(proposalEventsOf(ticket.id()))
                .containsExactly("ORDER_PROGRESS_APPLIED", "ORDER_PROGRESS_APPLIED");
    }

    @Test
    @DisplayName("a proposal the order refuses is refused cleanly, and the food stays where "
            + "the food is")
    void aProposalTheOrderRefusesIsRefusedCleanly() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-061", null, null, null, burger);
        TicketRow ticket = wiredTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        // An operator cancelled the order while the ticket was on the line. There
        // is no CANCELLED -> PREPARING edge, and there should not be one.
        jdbc.sql("UPDATE ordering.orders SET status = 'CANCELLED', version = version + 1 "
                + "WHERE id = :id").param("id", orderId).update();

        var outcome = wiredTickets.start(TENANT, item.id(), "cook", null);

        assertThat(outcome.applied())
                .as("the kitchen advance still applies: the food is where the food is, and a "
                        + "cook who cannot record readiness records nothing at all")
                .isTrue();
        assertThat(outcome.ticket().status()).isEqualTo(TicketStatus.IN_PRODUCTION);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(kitchenHistoryOf(orderId)).isEmpty();
        assertThat(proposalEventsOf(ticket.id()))
                .as("the refusal is on the ticket the branch reads, not only in a log")
                .containsExactly("ORDER_PROGRESS_REFUSED");
    }

    @Test
    @DisplayName("a replayed advance against an order that has moved on produces one effect "
            + "and no false refusal")
    void aReplayedAdvanceProducesOneEffect() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        brandRule(null, salad.productId(), null, StationRole.COLD);
        UUID orderId = seedConfirmedOrder("A-062", null, null, null, burger, salad);
        TicketRow ticket = wiredTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        List<TicketItemRow> items = store.itemsOf(TENANT, ticket.id());

        // An offline client's queue: every start, then every ready, then the whole
        // queue again because the acknowledgement never arrived.
        List<Runnable> queued = new ArrayList<>();
        items.forEach(item -> queued.add(() -> wiredTickets.start(TENANT, item.id(), "offline",
                null)));
        items.forEach(item -> queued.add(() -> wiredTickets.ready(TENANT, item.id(), "offline",
                null)));
        queued.forEach(Runnable::run);
        queued.forEach(Runnable::run);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.READY);
        assertThat(kitchenHistoryOf(orderId))
                .as("twelve replayed advances produce two transitions, not four")
                .containsExactly("CONFIRMED->PREPARING", "PREPARING->READY");
        assertThat(orderStore.find(TENANT, orderId).orElseThrow().version())
                .as("a replay that moved nothing must not bump the version either")
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_progress_proposals "
                        + "WHERE order_id = :id").param("id", orderId).query(Long.class).single())
                .as("one ledger row per kitchen fact, because the key is the ticket and the "
                        + "transition rather than the request")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a replayed PREPARING against an order already READY is answered with what "
            + "happened the first time, not with a refusal")
    void aStaleReplayIsAnsweredFromTheLedger() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-063", null, null, null, burger);
        TicketRow ticket = wiredTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
        TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();

        wiredTickets.start(TENANT, item.id(), "cook", null);
        wiredTickets.ready(TENANT, item.id(), "cook", null);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.READY);

        // The port called directly, because this is the offline client's replay
        // and the ticket itself will not roll up twice.
        var port = new OrderProgressAdapter(new OrderStateService(orderStore,
                new RecordingCapacity(),
                new OrderInventoryProcess(new JdbcOrderProcessStore(jdbc), REFUSES_INVENTORY,
                        JsonMapper.builder().build(), Clock.fixed(NOON, ZoneOffset.UTC)),
                new OrderAcceptancePolicyService(new JdbcPolicyResolver(jdbc,
                        JsonMapper.builder().build())),
                settlements, audit, event -> { }, Clock.fixed(NOON, ZoneOffset.UTC)));

        var replay = port.propose(TENANT, orderId, OrderProgressPort.OrderProgress.PREPARING,
                "kitchen-ticket:%s:PREPARING".formatted(ticket.id()), "KITCHEN_PREPARING", "USER",
                "offline", null);

        assertThat(replay)
                .as("a status comparison would say REFUSED here, and a refusal that is really "
                        + "a replay is a false alarm on a board")
                .isEqualTo(OrderProgressPort.ProposalOutcome.APPLIED);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.READY);
        assertThat(kitchenHistoryOf(orderId))
                .containsExactly("CONFIRMED->PREPARING", "PREPARING->READY");
    }

    @Test
    @DisplayName("a pickup handover completes the order and settles its tender; a delivery "
            + "order refuses the same proposal")
    void aPickupHandoverCompletesAndADeliveryOrderDoesNot() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID pickup = seedConfirmedOrder("A-064", null, null, null, burger);
        UUID delivery = seedConfirmedOrder("A-065", null, null, null, burger);
        jdbc.sql("UPDATE ordering.orders SET fulfillment_mode = 'DELIVERY' WHERE id = :id")
                .param("id", delivery).update();

        for (UUID orderId : List.of(pickup, delivery)) {
            TicketRow ticket = wiredTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);
            TicketItemRow item = store.itemsOf(TENANT, ticket.id()).getFirst();
            wiredTickets.start(TENANT, item.id(), "cook", null);
            wiredTickets.ready(TENANT, item.id(), "cook", null);
        }

        var port = new OrderProgressAdapter(new OrderStateService(orderStore,
                new RecordingCapacity(),
                new OrderInventoryProcess(new JdbcOrderProcessStore(jdbc), REFUSES_INVENTORY,
                        JsonMapper.builder().build(), Clock.fixed(NOON, ZoneOffset.UTC)),
                new OrderAcceptancePolicyService(new JdbcPolicyResolver(jdbc,
                        JsonMapper.builder().build())),
                settlements, audit, event -> { }, Clock.fixed(NOON, ZoneOffset.UTC)));

        var completed = port.propose(TENANT, pickup, OrderProgressPort.OrderProgress.COMPLETED,
                "handover:" + pickup, "KITCHEN_COMPLETED", "USER", "expo", null);
        var refused = port.propose(TENANT, delivery, OrderProgressPort.OrderProgress.COMPLETED,
                "handover:" + delivery, "KITCHEN_COMPLETED", "USER", "expo", null);

        assertThat(completed).isEqualTo(OrderProgressPort.ProposalOutcome.APPLIED);
        assertThat(statusOf(pickup)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(settlements.handovers)
                .as("ADR 0046: a completion that committed without its tender settling is an "
                        + "order the tenant was paid for and cannot refund")
                .containsExactly(pickup);
        assertThat(capacityReleases)
                .as("ADR 0036's slot is freed the moment the order stops occupying the kitchen")
                .containsExactly(pickup);

        assertThat(refused)
                .as("ADR 0014 moves a delivery shipment on its own evidence; a kitchen "
                        + "completing one would close it while the food is on a scooter")
                .isEqualTo(OrderProgressPort.ProposalOutcome.REFUSED);
        assertThat(statusOf(delivery)).isEqualTo(OrderStatus.READY);
    }

    @Test
    @DisplayName("a proposal naming another tenant's order moves nothing and is refused")
    void aProposalCannotReachAnotherTenantsOrder() {
        brandRule(null, burger.productId(), null, StationRole.GRILL);
        UUID orderId = seedConfirmedOrder("A-066", null, null, null, burger);

        var port = new OrderProgressAdapter(new OrderStateService(orderStore,
                new RecordingCapacity(),
                new OrderInventoryProcess(new JdbcOrderProcessStore(jdbc), REFUSES_INVENTORY,
                        JsonMapper.builder().build(), Clock.fixed(NOON, ZoneOffset.UTC)),
                new OrderAcceptancePolicyService(new JdbcPolicyResolver(jdbc,
                        JsonMapper.builder().build())),
                settlements, audit, event -> { }, Clock.fixed(NOON, ZoneOffset.UTC)));

        UUID stranger = UUID.randomUUID();
        var outcome = port.propose(stranger, orderId, OrderProgressPort.OrderProgress.PREPARING,
                "kitchen-ticket:cross-tenant:PREPARING", "KITCHEN_PREPARING", "USER", "cook", null);

        assertThat(outcome).isEqualTo(OrderProgressPort.ProposalOutcome.REFUSED);
        assertThat(statusOf(orderId))
                .as("an order id alone authorises nothing; the tenant is the boundary")
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_progress_proposals")
                        .query(Long.class).single())
                .as("a refused cross-tenant proposal must not even claim the key, or one "
                        + "tenant could burn another's idempotency keys")
                .isEqualTo(0L);
    }

    // --------------------------------------------------------------- the roll-up

    @Test
    @DisplayName("a ticket whose every line was cancelled does not report itself ready")
    void aFullyCancelledTicketIsNotReady() {
        TicketStatus rolled = KitchenStateMachine.rollUp(TicketStatus.IN_PRODUCTION,
                List.of(TicketItemStatus.CANCELLED, TicketItemStatus.CANCELLED));

        assertThat(rolled)
                .as("there is no food; saying ready would put an empty bag on the pass")
                .isEqualTo(TicketStatus.IN_PRODUCTION);
    }

    // -------------------------------------------------------------------- fixture

    private Resolution resolve(Catalogue node) {
        return store.resolveStation(TENANT, BRAND, branch, node.variantId(), node.productId())
                .map(resolved -> new Resolution(resolved.stationId(), resolved.level()))
                .orElse(new Resolution(fallbackStation, RoutingLevel.FALLBACK));
    }

    private void brandRule(UUID variantId, UUID productId, UUID categoryId, StationRole role) {
        stationService.route(new KitchenStationService.NewRoutingRule(
                TENANT, BRAND, null, variantId, productId, categoryId, role, null));
    }

    private void locationRule(UUID variantId, UUID productId, UUID categoryId, UUID stationId) {
        stationService.route(new KitchenStationService.NewRoutingRule(
                TENANT, BRAND, branch, variantId, productId, categoryId, null, stationId));
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'kitchen-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();
    }

    private UUID insertLocation(String code, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", id).param("tenantId", TENANT).param("brandId", BRAND)
                .param("code", code).param("slug", slug).update();
        return id;
    }

    private void seedCatalogue() {
        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """).param("id", catalogId).param("tenantId", TENANT).param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash',
                        now())
                """).param("id", publicationId).param("tenantId", TENANT).param("brandId", BRAND)
                .param("catalogId", catalogId).update();

        burger = seedProduct("BURGER", "HOT_FOOD");
        salad = seedProduct("SALAD", "COLD_FOOD");
        tea = seedProduct("TEA", "DRINKS");
    }

    private Catalogue seedProduct(String code, String categoryCode) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID categoryIdentifier = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """).param("id", productId).param("tenantId", TENANT).param("brandId", BRAND)
                .param("code", code).update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """).param("id", variantId).param("tenantId", TENANT).param("brandId", BRAND)
                .param("productId", productId).param("sku", "SKU-" + code).update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """).param("tenantId", TENANT).param("brandId", BRAND).param("catalogId", catalogId)
                .param("productId", productId).update();
        jdbc.sql("""
                INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, code,
                    sort_order, status)
                VALUES (:id, :tenantId, :brandId, :catalogId, :code, 0, 'ACTIVE')
                """).param("id", categoryIdentifier).param("tenantId", TENANT)
                .param("brandId", BRAND).param("catalogId", catalogId).param("code", categoryCode)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.category_products (tenant_id, brand_id, category_id,
                    product_id)
                VALUES (:tenantId, :brandId, :categoryId, :productId)
                """).param("tenantId", TENANT).param("brandId", BRAND)
                .param("categoryId", categoryIdentifier).param("productId", productId).update();

        return new Catalogue(productId, variantId, categoryIdentifier);
    }

    private void seedStations() {
        grillStation = stationService.create(new KitchenStationService.NewStation(
                TENANT, BRAND, branch, "GRILL", StationRole.GRILL, "Гриль", "Gril", "Grill",
                1, false)).id();
        coldStation = stationService.create(new KitchenStationService.NewStation(
                TENANT, BRAND, branch, "COLD", StationRole.COLD, "Холодный", "Sovuq", "Cold line",
                2, false)).id();
        fallbackStation = stationService.create(new KitchenStationService.NewStation(
                TENANT, BRAND, branch, "PASS", StationRole.EXPO, "Раздача", "Tarqatish", "Pass",
                3, true)).id();
    }

    /**
     * An order sitting at CONFIRMED with its lines, inserted rather than checked
     * out. Checkout is ADR 0019's own suite; what this suite needs from an order
     * is its lines, its promise, and its status.
     */
    private UUID seedConfirmedOrder(String number, Instant promisedAt, Integer prepMinutes,
            Integer travelMinutes, Catalogue... lines) {

        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """).param("id", quoteId).param("tenantId", TENANT).param("brandId", BRAND)
                .param("locationId", branch).param("publicationId", publicationId).update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'PICKUP', 'UZS',
                        'ACTIVE', :guest, now() + interval '1 hour')
                """).param("id", cartId).param("tenantId", TENANT).param("brandId", BRAND)
                .param("locationId", branch).param("channelId", channelId)
                .param("guest", "guest-" + number).update();

        Map<String, Object> order = new java.util.HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", branch);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("guest", "guest-" + number);
        order.put("promisedAt", promisedAt == null ? null
                : java.time.OffsetDateTime.ofInstant(promisedAt, ZoneOffset.UTC));
        order.put("basis", promisedAt == null ? "NOT_PROMISED" : "PREPARATION_BAND");
        order.put("prepMinutes", prepMinutes);
        order.put("travelMinutes", travelMinutes);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, promised_at, promise_basis, promise_prep_minutes,
                    promise_travel_minutes, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                    :guest, 'PICKUP', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS',
                    50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :guest,
                    :promisedAt, :basis, :prepMinutes, :travelMinutes, 1, now())
                """).params(order).update();

        // ADR 0039 (V0029) gave every order line a revision to belong to. Order
        // lines are inserted directly here, so revision 1 has to exist first.
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source,
                    pricing_quote_id, pricing_context_hash, currency, subtotal_minor, tax_minor,
                    total_minor)
                VALUES (:orderId, 1, :tenantId, 'CHECKOUT', :quoteId, 'hash', 'UZS', 50000, 0,
                        50000)
                """).param("orderId", orderId).param("tenantId", TENANT).param("quoteId", quoteId)
                .update();

        int lineNumber = 1;
        for (Catalogue line : lines) {
            jdbc.sql("""
                    INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number,
                        source_product_id, source_variant_id, product_name_snapshot, quantity,
                        unit_amount_minor, base_amount_minor, final_amount_minor, tax_amount_minor)
                    VALUES (:id, :tenantId, :orderId, :lineNumber, :productId, :variantId,
                        'Dish', 1, 50000, 50000, 50000, 0)
                    """).param("id", UUID.randomUUID()).param("tenantId", TENANT)
                    .param("orderId", orderId).param("lineNumber", lineNumber++)
                    .param("productId", line.productId()).param("variantId", line.variantId())
                    .update();
        }
        return orderId;
    }

    private record Catalogue(UUID productId, UUID variantId, UUID categoryId) { }

    private record Resolution(UUID stationId, RoutingLevel level) { }

    private OrderStatus statusOf(UUID orderId) {
        return orderStore.find(TENANT, orderId).orElseThrow().status();
    }

    private List<String> kitchenHistoryOf(UUID orderId) {
        return orderStore.history(TENANT, orderId).stream()
                .filter(row -> "KITCHEN_PROGRESS".equals(row.trigger()))
                .map(row -> row.fromStatus() + "->" + row.toStatus())
                .toList();
    }

    private List<String> proposalEventsOf(UUID ticketId) {
        return store.eventsOf(TENANT, ticketId).stream()
                .filter(event -> "ORDER_PROPOSAL".equals(event.trigger()))
                .map(JdbcKitchenStore.TicketEventRow::reasonCode)
                .toList();
    }

    /**
     * Nothing here may touch a reservation, so a call is a failure rather than a
     * recorded fact. A proposal that reached inventory would be a change nobody
     * meant to make, and a stand-in that quietly answered it would hide it.
     */
    private static final InventoryReservationPort REFUSES_INVENTORY =
            new InventoryReservationPort() {

                @Override
                public ReservationResult reserveForQuote(UUID tenantId, UUID brandId,
                        UUID locationId, UUID quoteId, Map<UUID, Integer> quantitiesByVariant) {
                    throw new AssertionError("A kitchen proposal must not reserve stock");
                }

                @Override
                public boolean commit(UUID tenantId, UUID quoteId) {
                    throw new AssertionError("A kitchen proposal must not commit a reservation");
                }

                @Override
                public boolean release(UUID tenantId, UUID quoteId) {
                    throw new AssertionError("A kitchen proposal must not release a reservation");
                }
            };

    /** ADR 0036's ceiling. A completion frees the slot; nothing else does. */
    private final class RecordingCapacity implements LocationCapacityPort {

        @Override
        public CapacityOutcome claimCapacity(UUID tenantId, UUID brandId, UUID locationId,
                UUID holdId) {
            throw new AssertionError("A kitchen proposal must not claim capacity");
        }

        @Override
        public boolean releaseCapacity(UUID tenantId, UUID holdId) {
            capacityReleases.add(holdId);
            return true;
        }
    }

    /** ADR 0046's handover, which a pickup completion is the only kitchen route to. */
    private static final class RecordingSettlements implements OrderSettlementPort {

        private final List<UUID> handovers = new CopyOnWriteArrayList<>();
        private final List<UUID> terminals = new CopyOnWriteArrayList<>();

        @Override
        public java.util.Optional<PlannedSettlement> planSettlement(SettlementRequest request) {
            throw new AssertionError("A kitchen proposal must not plan a settlement");
        }

        @Override
        public void recordHandover(UUID tenantId, UUID orderId, String actor) {
            handovers.add(orderId);
        }

        @Override
        public void recordTerminalOutcome(UUID tenantId, UUID orderId, String reasonCode,
                String actor) {
            terminals.add(orderId);
        }
    }

    /** Records what the kitchen proposed, which is all this module is entitled to do. */
    private static final class RecordingOrderProgressPort implements OrderProgressPort {

        private final Map<UUID, List<OrderProgress>> proposed = new ConcurrentHashMap<>();
        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Override
        public ProposalOutcome propose(UUID tenantId, UUID orderId, OrderProgress progress,
                String idempotencyKey, String reasonCode, String actorType, String actorId,
                String correlationId) {
            keys.add(idempotencyKey);
            proposed.computeIfAbsent(orderId, key -> new CopyOnWriteArrayList<>()).add(progress);
            return ProposalOutcome.APPLIED;
        }

        List<OrderProgress> of(UUID orderId) {
            return proposed.getOrDefault(orderId, List.of());
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
