package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.QuoteOutcome;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.application.DeliveryPlanningService;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingRunner;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingService;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryExceptionReason;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryQuoteStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore.ClaimedJob;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJournal;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The durable half of ADR 0014, against a real PostgreSQL.
 *
 * <p>Every property here is one only the database can hold. The single-winner
 * rule is three partial unique indexes in V0054, the lease is a conditional
 * update racing itself, and the idempotency that stops a replayed tick booking a
 * second courier is a unique key on the attempt row. A fake that imitated any of
 * them would let this suite pass while the statement was wrong — and the statement
 * being wrong is a second courier at somebody's door and a second commission on
 * somebody's invoice.
 */
class DeliverySourcingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** Amir Temur square, where the branch is. */
    private static final double BRANCH_LATITUDE = 41.311081;

    private static final double BRANCH_LONGITUDE = 69.240562;

    /** A Tuesday, 17:00 in Tashkent. */
    private static final Instant CONFIRMED = Instant.parse("2026-08-25T12:00:00Z");

    private static final UUID COURIER_ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COURIER_TWO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private JdbcDeliveryPlanStore planStore;
    private JdbcSourcingJobStore jobStore;
    private JdbcAssignmentStore assignmentStore;
    private JdbcDeliveryExceptionStore exceptionStore;
    private JdbcSourcingJournal journal;
    private DeliveryPlanningService planning;
    private DeliverySourcingRunner runner;
    private RecordingBookings bookings;
    private ConfigurableFleet fleet;
    private ConfigurableOrders orders;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID noorBinding;
    private UUID yandexBinding;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for delivery sourcing tests");
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
                TRUNCATE TABLE
                    fulfillment.delivery_exceptions,
                    fulfillment.delivery_sourcing_jobs,
                    fulfillment.assignment_attempts,
                    fulfillment.delivery_quotes,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    integration.bindings,
                    integration.installations,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(CONFIRMED);
        planStore = new JdbcDeliveryPlanStore(jdbc);
        jobStore = new JdbcSourcingJobStore(jdbc);
        assignmentStore = new JdbcAssignmentStore(jdbc);
        exceptionStore = new JdbcDeliveryExceptionStore(jdbc);
        journal = new JdbcSourcingJournal(
                assignmentStore,
                new JdbcDeliveryQuoteStore(jdbc, JsonMapper.builder().build()),
                exceptionStore);

        JdbcDispatchBranchStore branches = new JdbcDispatchBranchStore(jdbc);
        orders = new ConfigurableOrders();
        bookings = new RecordingBookings();
        fleet = new ConfigurableFleet();

        planning = new DeliveryPlanningService(orders, planStore, jobStore, branches, unconfigured(), clock);
        DeliverySourcingService sourcing = new DeliverySourcingService(fleet, bookings, journal, unconfigured(), clock);
        runner = new DeliverySourcingRunner(
                sourcing,
                journal,
                orders,
                planStore,
                jobStore,
                branches,
                clock,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                12);

        seedTenancy();
    }

    // ------------------------------------------------------------- the plan

    @Test
    @DisplayName("a confirmed delivery order produces one plan and one job, and a replay adds " + "neither")
    void aConfirmationProducesOnePlanAndOneJob() {
        UUID orderId = seedDeliveryOrder();

        DeliveryPlan first =
                planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
        DeliveryPlan replay =
                planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();

        // Two plans for one order is two sets of sourcing jobs racing, which is
        // how two couriers arrive. The unique index decides it, not a read.
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("fulfillment.delivery_plans")).isEqualTo(1);
        assertThat(count("fulfillment.delivery_sourcing_jobs")).isEqualTo(1);

        // The whole time model, computed once from the confirmation instant.
        // Fifteen minutes of preparation, ADR 0014's provisional lead and buffer.
        assertThat(first.pickup().estimatedReadyAt()).isEqualTo(CONFIRMED.plus(Duration.ofMinutes(15)));
        assertThat(first.pickup().sourceAt()).isEqualTo(CONFIRMED);
        assertThat(first.pickup().pickupWindowEnd()).isEqualTo(CONFIRMED.plus(Duration.ofMinutes(30)));
        assertThat(first.pickup().branchZone()).isEqualTo(TASHKENT);
        assertThat(first.status()).isEqualTo(PlanStatus.PLANNED);
        // Snapshotted at checkout and never re-derived from today's zones.
        assertThat(first.customerDeliveryFeeMinor()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("the kitchen revising its estimate moves the sourcing job, not the promise it " + "was made from")
    void aRevisedPreparationEstimateMovesTheJob() {
        orders.preparation = Duration.ofHours(2);
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        assertThat(dueTime()).isEqualTo(CONFIRMED.plus(Duration.ofMinutes(105)));

        // Twenty minutes into the two hours, the kitchen says three. Recalculated
        // from the confirmation instant rather than from now, so a revision that
        // took twenty minutes to arrive does not push the promise out by twenty
        // minutes on top of the hour it actually moved.
        clock.set(CONFIRMED.plus(Duration.ofMinutes(20)));
        assertThat(planning.repriceSchedule(TENANT, plan.id(), Duration.ofHours(3)))
                .isTrue();

        assertThat(dueTime()).isEqualTo(CONFIRMED.plus(Duration.ofMinutes(165)));
        // And the plan a courier would be sent against is untouched until somebody
        // decides whether the existing booking can survive the change: neither
        // verified partner supports reschedule, so that is a cancel-and-re-source
        // decision and not one this method may make silently.
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().pickup().pickupWindowStart())
                .isEqualTo(CONFIRMED.plus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("a plan belongs to the tenant it was created for and is invisible to another")
    void aPlanIsNotReadableByAnotherTenant() {
        UUID orderId = seedDeliveryOrder();
        DeliveryPlan plan =
                planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();

        // An entity id alone is never proof of ownership: the other tenant holds
        // the id and gets nothing, which is the same answer as "no such plan".
        assertThat(planStore.find(OTHER_TENANT, plan.id())).isEmpty();
        assertThat(planStore.findByOrder(OTHER_TENANT, orderId)).isEmpty();
    }

    // ---------------------------------------------------------- the scheduler

    @Test
    @DisplayName("a job is claimed by exactly one worker, and a worker that dies loses its lease")
    void oneWorkerClaimsAJobAndADeadWorkerLosesIt() {
        assertThat(planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED))
                .isPresent();

        List<ClaimedJob> first = jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker-a");
        List<ClaimedJob> second = jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker-b");

        assertThat(first).hasSize(1);
        assertThat(second)
                .as("two workers holding one job is two workers sourcing the same order")
                .isEmpty();

        // worker-a is now dead: it never completes, never reschedules, and its
        // lease is the only thing that frees the order.
        Instant afterLease = CONFIRMED.plus(Duration.ofMinutes(3));
        List<ClaimedJob> recovered = jobStore.claim(afterLease, Duration.ofMinutes(2), 10, "worker-c");

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().jobId()).isEqualTo(first.getFirst().jobId());
        assertThat(recovered.getFirst().claimedAttempt())
                .as("the attempt count is what tells an operator this order was re-run")
                .isEqualTo(2);
        assertThat(recovered.getFirst().leaseToken())
                .isNotEqualTo(first.getFirst().leaseToken());
    }

    @Test
    @DisplayName("a job that is not due yet is not claimed, however often the scheduler polls")
    void aJobBeforeItsDueTimeIsNotClaimed() {
        orders.preparation = Duration.ofHours(2);
        assertThat(planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED))
                .isPresent();

        // The whole point of ADR 0014: a two-hour order is sourced near readiness,
        // not at confirmation, or the courier waits unpaid at the counter.
        assertThat(jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker"))
                .isEmpty();

        Instant due = CONFIRMED.plus(Duration.ofMinutes(105));
        assertThat(jobStore.claim(due, Duration.ofMinutes(2), 10, "worker")).hasSize(1);
    }

    @Test
    @DisplayName("a worker whose lease expired cannot complete the job the next worker holds")
    void aLostLeaseCannotFinishSomebodyElsesJob() {
        assertThat(planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED))
                .isPresent();
        ClaimedJob stale =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker-a").getFirst();
        Instant later = CONFIRMED.plus(Duration.ofMinutes(3));
        ClaimedJob live =
                jobStore.claim(later, Duration.ofMinutes(2), 10, "worker-b").getFirst();

        assertThat(jobStore.complete(stale.jobId(), stale.leaseToken(), later)).isFalse();
        assertThat(jobStore.complete(live.jobId(), live.leaseToken(), later)).isTrue();
    }

    // ------------------------------------------------------ the single winner

    @Test
    @DisplayName("two workers booking the same plan produce one shipment and one accepted attempt")
    void twoBookingsProduceOneShipment() throws Exception {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();

        // Two attempts, as two workers whose leases overlapped would produce. The
        // first has to be closed for the second to open, because ux_attempt_one_offered
        // permits one live attempt — so this is the harder race: two attempts that
        // both reached a partner and both got a booking back.
        UUID firstAttempt = openPartnerAttempt(plan, noorBinding, "key-1");
        assignmentStore.close(TENANT, firstAttempt, AttemptStatus.REQUESTED, null, null, false, CONFIRMED);
        UUID secondAttempt = openPartnerAttempt(plan, yandexBinding, "key-2");

        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));

        List<Optional<UUID>> results = inParallel(
                () -> transactions.execute(status -> assignmentStore.win(new JdbcAssignmentStore.WinningAttempt(
                        TENANT,
                        firstAttempt,
                        SourceType.PARTNER,
                        AttemptStatus.REQUESTED,
                        "noor-delivery",
                        "noor-1",
                        CONFIRMED))),
                () -> transactions.execute(status -> assignmentStore.win(new JdbcAssignmentStore.WinningAttempt(
                        TENANT,
                        secondAttempt,
                        SourceType.PARTNER,
                        AttemptStatus.REQUESTED,
                        "yandex-delivery",
                        "yandex-1",
                        CONFIRMED))));

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(count("fulfillment.shipments")).isEqualTo(1);
        assertThat(countWhere("fulfillment.assignment_attempts", "status = 'ACCEPTED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the second courier to tap accept is told somebody else took it")
    void onlyOneCourierWinsAnOffer() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        seedCourier(COURIER_ONE, "C-001");
        seedCourier(COURIER_TWO, "C-002");

        UUID offerOne = openInternalOffer(plan, COURIER_ONE, "offer-1");
        assertThat(journal.acceptOffer(TENANT, offerOne, COURIER_ONE, CONFIRMED))
                .isTrue();

        // The same tap arriving twice, which is a phone on a bad connection.
        assertThat(journal.acceptOffer(TENANT, offerOne, COURIER_ONE, CONFIRMED))
                .isFalse();

        // And a second courier offered the same plan by a worker whose lease had
        // expired. The offer row is writable — the accepted attempt no longer
        // holds ux_attempt_one_offered — so the only thing standing between this
        // and two couriers at one door is the shipment index.
        UUID offerTwo = openInternalOffer(plan, COURIER_TWO, "offer-2");
        assertThat(journal.acceptOffer(TENANT, offerTwo, COURIER_TWO, CONFIRMED))
                .as("a courier who taps a second later is told somebody else took it, not that " + "something failed")
                .isFalse();

        assertThat(count("fulfillment.shipments")).isEqualTo(1);
        assertThat(countWhere("fulfillment.assignment_attempts", "status = 'ACCEPTED'"))
                .isEqualTo(1);
        assertThat(assignmentStore.findShipment(TENANT, plan.id()).orElseThrow().courierId())
                .isEqualTo(COURIER_ONE);
    }

    @Test
    @DisplayName("a courier cannot accept an offer that was made to somebody else")
    void anOfferBelongsToTheCourierItWasMadeTo() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        seedCourier(COURIER_ONE, "C-001");
        seedCourier(COURIER_TWO, "C-002");
        UUID offer = openInternalOffer(plan, COURIER_ONE, "offer-1");

        assertThat(journal.acceptOffer(TENANT, offer, COURIER_TWO, CONFIRMED)).isFalse();
        assertThat(journal.acceptOffer(OTHER_TENANT, offer, COURIER_ONE, CONFIRMED))
                .isFalse();
        assertThat(count("fulfillment.shipments")).isZero();
    }

    @Test
    @DisplayName("an offer that lapsed cannot be accepted and frees the plan for the next courier")
    void alapsedOfferCannotBeAccepted() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        seedCourier(COURIER_ONE, "C-001");
        UUID offer = openInternalOffer(plan, COURIER_ONE, "offer-1");

        Instant afterExpiry = CONFIRMED.plus(Duration.ofMinutes(5));
        assertThat(journal.acceptOffer(TENANT, offer, COURIER_ONE, afterExpiry)).isFalse();

        // And the row does not go on holding ux_attempt_one_offered, or the next
        // courier could never be asked.
        assertThat(journal.expireLapsedOffers(TENANT, plan.id(), afterExpiry)).isEqualTo(1);
        assertThat(journal.progress(TENANT, plan.id(), CONFIRMED).outstandingOffer())
                .isNull();
    }

    // --------------------------------------------------------- the whole loop

    @Test
    @DisplayName("an order ready to fulfil is planned, sourced, and booked with a partner exactly " + "once")
    void anOrderIsPlannedSourcedAndBooked() {
        UUID orderId = seedDeliveryOrder();
        DeliveryPlan plan =
                planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        SourcingDecision decision = runner.run(job).orElseThrow();

        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(bookings.booked).hasSize(1);
        // What reaches the partner is a provider-neutral command naming the order
        // and both ends of the journey; CamelShipmentBookingPort turns exactly this
        // into the DeliveryOperation the route and the adapters wait on.
        BookingCommand command = bookings.booked.getFirst();
        assertThat(command.horecaosReference()).isEqualTo("D-" + sequence);
        assertThat(command.prepaid()).isTrue();
        assertThat(command.pickup().latitude()).isEqualTo(BRANCH_LATITUDE);

        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status()).isEqualTo(PlanStatus.ASSIGNED);
        var shipment = assignmentStore.findShipment(TENANT, plan.id()).orElseThrow();
        assertThat(shipment.sourceType()).isEqualTo(SourceType.PARTNER);
        assertThat(shipment.externalShipmentId()).isEqualTo("noor-ref-1");
        assertThat(shipment.orderId()).isEqualTo(orderId);
        // The job is finished rather than left to wake again and book a second one.
        assertThat(countWhere("fulfillment.delivery_sourcing_jobs", "status = 'COMPLETED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a tick replayed after its worker died calls the partner once, not twice")
    void aReplayedTickDoesNotBookTwice() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();

        // The partner acted and the worker died before it could record the answer,
        // which is the shape of every at-least-once scheduling bug that ends in two
        // couriers. The attempt row was committed before the call, so the replay
        // finds it.
        bookings.status = BookingStatus.RETRYABLE;
        ClaimedJob first =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker-a").getFirst();
        runner.run(first);

        UUID attemptId = onlyAttempt();
        assignmentStore.close(TENANT, attemptId, AttemptStatus.FAILED, "TIMEOUT", null, false, CONFIRMED);
        bookings.booked.clear();
        bookings.status = BookingStatus.BOOKED;

        Instant later = CONFIRMED.plus(Duration.ofSeconds(30));
        clock.set(later);
        ClaimedJob replay =
                jobStore.claim(later, Duration.ofMinutes(2), 10, "worker-b").getFirst();
        runner.run(replay);

        // The second tick walks to the next partner rather than re-sending the
        // first partner's command, because the first attempt is recorded as one
        // that answered.
        assertThat(bookings.booked).hasSize(1);
        assertThat(bookings.booked.getFirst().bindingId()).isEqualTo(yandexBinding);
        assertThat(countWhere("fulfillment.assignment_attempts", "status = 'ACCEPTED'"))
                .isEqualTo(1);
        assertThat(count("fulfillment.shipments")).isEqualTo(1);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status()).isEqualTo(PlanStatus.ASSIGNED);
    }

    @Test
    @DisplayName("a tick that finds the plan already carrying a shipment books nobody else")
    void aPlanThatAlreadyHasAShipmentIsNotSourcedAgain() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();

        // The state a worker leaves behind when it wins the compare-and-set and
        // dies before it can record that it did: a live shipment, and a plan that
        // still reads as unsourced. The second shipment would lose to the unique
        // index — but the partner call that produced it would already have
        // dispatched somebody and would bill for it.
        UUID attempt = openPartnerAttempt(plan, noorBinding, "won-then-died");
        assertThat(assignmentStore.win(new JdbcAssignmentStore.WinningAttempt(
                        TENANT,
                        attempt,
                        SourceType.PARTNER,
                        AttemptStatus.REQUESTED,
                        "noor-delivery",
                        "noor-1",
                        CONFIRMED)))
                .isPresent();

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        assertThat(runner.run(job)).isEmpty();

        assertThat(bookings.booked).isEmpty();
        assertThat(count("fulfillment.shipments")).isEqualTo(1);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status()).isEqualTo(PlanStatus.ASSIGNED);
        assertThat(countWhere("fulfillment.delivery_sourcing_jobs", "status = 'COMPLETED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a tick whose attempt already answered asks the partner nothing at all")
    void anAnsweredAttemptIsNeverResent() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();

        // The row a previous tick wrote before it called, under the exact key the
        // partner saw, left in the state a partner answer leaves it in. The key is
        // spelled out rather than borrowed from the service, so a change to the
        // derivation fails here rather than silently letting a replay call again.
        String commandKey = UUID.nameUUIDFromBytes("horecaos.delivery-attempt:%s:%s:0"
                        .formatted(plan.id(), noorBinding)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        UUID attempt = openPartnerAttempt(plan, noorBinding, commandKey);
        assignmentStore.close(TENANT, attempt, AttemptStatus.UNCERTAIN, "TIMEOUT", "noor-ref-1", true, CONFIRMED);

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        SourcingDecision decision = runner.run(job).orElseThrow();

        assertThat(bookings.booked)
                .as("a partner that may already have accepted must never be asked again")
                .isEmpty();
        assertThat(decision.reason()).isEqualTo(SourcingDecision.AWAITING_RECONCILIATION);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status())
                .isEqualTo(PlanStatus.MANUAL_ACTION_REQUIRED);
        assertThat(exceptionStore.open(TENANT, plan.id()))
                .extracting(JdbcDeliveryExceptionStore.OpenException::reasonCode)
                .containsExactly(DeliveryExceptionReason.AWAITING_RECONCILIATION);
    }

    // ------------------------------------------------------- quotes and score

    @Test
    @DisplayName("several partners answer, the cheapest is booked, and every answer is kept")
    void thecheapestQuotedPartnerWins() {
        assertThat(planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED))
                .isPresent();
        bookings.quotes.put(noorBinding, QuoteOutcome.priced(28_000L, "UZS", 480, 1_500, 3_400, 900));
        bookings.quotes.put(yandexBinding, QuoteOutcome.priced(19_000L, "UZS", 600, 1_800, 3_400, 1_200));

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        runner.run(job);

        // Noor is the narrower binding and would have been asked first; the price
        // is the reason it was not, and every som of difference is one the tenant
        // or the platform absorbs because the customer's fee never moves.
        assertThat(bookings.booked).hasSize(1);
        assertThat(bookings.booked.getFirst().bindingId()).isEqualTo(yandexBinding);

        // Both answers are evidence, not only the winner's: "why did this go to
        // the more expensive one" is answered by the row that says it did not.
        assertThat(count("fulfillment.delivery_quotes")).isEqualTo(2);
        assertThat(countWhere("fulfillment.delivery_quotes", "quote_validity_source = 'HORECAOS_POLICY'"))
                .as("neither verified partner returns an expiry, so the TTL is ours and is " + "recorded as ours")
                .isEqualTo(2);
        assertThat(countWhere("fulfillment.assignment_attempts", "quote_id IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a partner that refuses to quote is not booked, and the escalation says the " + "partners were exhausted")
    void aPartnerThatRefusesAQuoteIsNotBooked() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        bookings.quotes.put(noorBinding, QuoteOutcome.unavailable("CancelledOutOfZone"));
        bookings.quotes.put(yandexBinding, QuoteOutcome.unavailable("PerformerNotFound"));

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        SourcingDecision decision = runner.run(job).orElseThrow();

        assertThat(bookings.booked).isEmpty();
        assertThat(decision.reason()).isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
        assertThat(countWhere("fulfillment.delivery_quotes", "status = 'REFUSED'"))
                .isEqualTo(2);
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status())
                .isEqualTo(PlanStatus.MANUAL_ACTION_REQUIRED);
        assertThat(exceptionStore.open(TENANT, plan.id()))
                .extracting(JdbcDeliveryExceptionStore.OpenException::reasonCode)
                .containsExactly(DeliveryExceptionReason.NO_PROVIDER);
    }

    @Test
    @DisplayName("a sweeper reaching the same conclusion twice opens one exception, not two")
    void anExceptionIsOpenedOnce() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();

        journal.raiseException(
                TENANT, BRAND, branch, plan.id(), DeliveryExceptionReason.NO_PROVIDER, "first", CONFIRMED);
        journal.raiseException(
                TENANT, BRAND, branch, plan.id(), DeliveryExceptionReason.NO_PROVIDER, "second", CONFIRMED);

        assertThat(exceptionStore.open(TENANT, plan.id())).hasSize(1);
        assertThat(exceptionStore.open(TENANT, plan.id()).getFirst().detail()).isEqualTo("first");
    }

    // ------------------------------------------------------------- the fleet

    @Test
    @DisplayName("a courier with capacity is offered the order and no partner is called")
    void anAvailableCourierIsOfferedTheOrder() {
        DeliveryPlan plan = planning.open(TENANT, BRAND, branch, seedDeliveryOrder(), CONFIRMED)
                .orElseThrow();
        seedCourier(COURIER_ONE, "C-001");
        fleet.candidates = List.of(new InternalFleetPort.FleetCandidate(COURIER_ONE, 60, 0, 2, 400, 1));

        ClaimedJob job =
                jobStore.claim(CONFIRMED, Duration.ofMinutes(2), 10, "worker").getFirst();
        SourcingDecision decision = runner.run(job).orElseThrow();

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(bookings.booked)
                .as("the commission is only paid when the fleet could not take it")
                .isEmpty();
        assertThat(countWhere("fulfillment.assignment_attempts", "status = 'OFFERED' AND source_type = 'INTERNAL'"))
                .isEqualTo(1);

        // The job wakes again just after the offer lapses, so a courier who never
        // answers costs one offer TTL and not the whole pickup window.
        assertThat(dueTime()).isEqualTo(CONFIRMED.plusSeconds(61));

        // And the courier can take it, which is the other half of the loop.
        UUID offer = onlyAttempt();
        assertThat(journal.acceptOffer(TENANT, offer, COURIER_ONE, CONFIRMED)).isTrue();
        assertThat(assignmentStore.findShipment(TENANT, plan.id()).orElseThrow().sourceType())
                .isEqualTo(SourceType.INTERNAL);
    }

    // -------------------------------------------------------------- helpers

    private UUID openPartnerAttempt(DeliveryPlan plan, UUID bindingId, String key) {
        return journal.openPartnerAttempt(
                        new uz.horecaos.platform.fulfillment.application.SourcingJournal.PartnerAttempt(
                                TENANT,
                                plan.id(),
                                bindingId,
                                key,
                                null,
                                SourcingDecision.NO_INTERNAL_CANDIDATE,
                                null,
                                0,
                                CONFIRMED))
                .attemptId();
    }

    private UUID openInternalOffer(DeliveryPlan plan, UUID courierId, String key) {
        return journal.openInternalOffer(new uz.horecaos.platform.fulfillment.application.SourcingJournal.InternalOffer(
                        TENANT,
                        plan.id(),
                        courierId,
                        key,
                        CONFIRMED.plusSeconds(60),
                        SourcingDecision.FLEET_AVAILABLE,
                        null,
                        0,
                        CONFIRMED))
                .attemptId();
    }

    private UUID onlyAttempt() {
        return jdbc.sql("SELECT id FROM fulfillment.assignment_attempts ORDER BY sequence_number " + "DESC LIMIT 1")
                .query(UUID.class)
                .single();
    }

    private Instant dueTime() {
        return jdbc.sql("SELECT due_at FROM fulfillment.delivery_sourcing_jobs")
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long countWhere(String table, String predicate) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate)
                .query(Long.class)
                .single();
    }

    private static <T> List<T> inParallel(Callable<T> first, Callable<T> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = pool.invokeAll(List.of(first, second));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    // ---------------------------------------------------------------- seeding

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "sourcing-tenant").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).param("slug", "other-tenant").update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source,
                    address_line, district, city, landmark, contact_phone)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0, :latitude, :longitude, 'MERCHANT_PIN',
                        'Amir Temur 1', 'Yunusobod', 'Toshkent', 'Beside the fountain',
                        '+998712000000')
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("latitude", BRANCH_LATITUDE)
                .param("longitude", BRANCH_LONGITUDE)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status)
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
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash',
                        now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        // Two delivery partners on this branch, narrowest first. Noor is the
        // location binding and Yandex the brand one, which is the order ADR 0026
        // resolves them in and therefore the order scoring has to beat to prove it
        // did anything.
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('noor-test', 'DELIVERY', 'noor-delivery', 'https://noor.test', false,
                        'noor.test'),
                       ('yandex-test', 'DELIVERY', 'yandex-delivery', 'https://yandex.test', false,
                        'yandex.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        noorBinding = seedBinding("noor-delivery", "noor-test", branch);
        yandexBinding = seedBinding("yandex-delivery", "yandex-test", null);
    }

    private UUID seedBinding(String providerType, String environment, @Nullable UUID locationId) {
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', :providerType, :environment, :providerType,
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("secret", "horecaos:test:provider_delivery:tenant:" + providerType)
                .update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id,
                    location_id, status, priority)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .update();
        return bindingId;
    }

    private void seedCourier(UUID courierId, String reference) {
        UUID typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :tenantId, :code, 'Scooter', 'SCOOTER', 2, 60, 'ACTIVE')
                """)
                .param("id", typeId)
                .param("tenantId", TENANT)
                .param("code", "SCOOTER-" + reference)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("tenantId", TENANT)
                .param("typeId", typeId)
                .param("subject", "keycloak-" + reference)
                .param("reference", reference)
                .update();
    }

    /**
     * The confirmed delivery order a plan belongs to. Checkout is ADR 0019's own
     * suite; what a plan needs from an order is that it exists, is this branch's,
     * and was confirmed.
     */
    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "sourcing-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "D-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        orders.reference = "D-" + sequence;
        orders.orderId = orderId;
        return orderId;
    }

    /** Nothing configured at any scope, which is the state every tenant starts in. */
    private static PolicyResolver unconfigured() {
        return new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };
    }

    // ------------------------------------------------------------------ fakes

    /** What ordering will supply once it implements the port. */
    private static final class ConfigurableOrders implements DeliveryOrderPort {

        /** Set by {@code seedDeliveryOrder()} before any test calls {@link #deliveryOrder}. */
        private @Nullable UUID orderId;

        private @Nullable String reference;
        private Duration preparation = Duration.ofMinutes(15);

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            if (!orderId.equals(this.orderId)) {
                return Optional.empty();
            }
            return Optional.of(new DeliveryOrder(
                    orderId,
                    Objects.requireNonNull(reference, "seedDeliveryOrder() must run before deliveryOrder() is called"),
                    preparation,
                    12_000L,
                    null,
                    "UZS",
                    true,
                    50_000L,
                    new Waypoint(41.325, 69.281, "Home", "Customer", "+998900000002", null, "2", "5", "17")));
        }
    }

    private static final class ConfigurableFleet implements InternalFleetPort {

        private List<FleetCandidate> candidates = List.of();

        @Override
        public List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId, int distanceMeters) {
            return candidates;
        }
    }

    /** Records what sourcing asked a partner to do, without a Camel context. */
    private final class RecordingBookings implements ShipmentBookingPort {

        private final List<BookingCommand> booked = new ArrayList<>();
        private final java.util.Map<UUID, QuoteOutcome> quotes = new java.util.HashMap<>();
        private BookingStatus status = BookingStatus.BOOKED;
        private int references;

        @Override
        public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
            return List.of(
                    new PartnerOption(noorBinding, "noor-delivery", false, true),
                    new PartnerOption(yandexBinding, "yandex-delivery", true, true));
        }

        @Override
        public QuoteOutcome quote(BookingCommand command) {
            return quotes.getOrDefault(
                    command.bindingId(), QuoteOutcome.unavailable(ShipmentBookingPort.QUOTE_NOT_WIRED));
        }

        @Override
        public BookingReceipt book(BookingCommand command) {
            booked.add(command);
            String providerType = command.bindingId().equals(noorBinding) ? "noor-delivery" : "yandex-delivery";
            String prefix = command.bindingId().equals(noorBinding) ? "noor-ref-" : "yandex-ref-";
            return BookingReceipt.of(
                    status,
                    command,
                    providerType,
                    status == BookingStatus.BOOKED ? prefix + ++references : null,
                    null,
                    null);
        }
    }

    /** A clock the tests move, because most of ADR 0014 is about elapsed time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant value) {
            this.now = value;
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
}
