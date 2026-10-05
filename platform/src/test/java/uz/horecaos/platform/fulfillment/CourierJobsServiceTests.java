package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Job;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.JobStatus;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Offer;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Result;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Step;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.application.CourierJobsService;
import uz.horecaos.platform.fulfillment.domain.Haversine;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcCourierJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The courier's side of ADR 0014's attempt and shipment, against a real PostgreSQL
 * (gap map row 3.9).
 *
 * <p>What only the database can hold is asserted here and not by a mock: that a tap on an offer
 * which another tap already took changes nothing (a conditional update racing itself), that an
 * id belonging to somebody else matches no row, that a shipment cannot move backwards, and that
 * the customer's door is not decrypted for a question that does not need it.
 */
class CourierJobsServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID COURIER = UUID.randomUUID();
    private static final UUID OTHER_COURIER = UUID.randomUUID();

    private static final double BRANCH_LATITUDE = 41.311081;
    private static final double BRANCH_LONGITUDE = 69.240562;
    private static final double DOOR_LATITUDE = 41.33;
    private static final double DOOR_LONGITUDE = 69.24;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private CourierJobsService jobs;
    private RecordingRealtime realtime;
    private RecordingOrders orders;
    private JdbcSourcingJobStore sourcingJobs;
    private TransactionTemplate tx;
    private Instant now;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the courier jobs tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("""
                TRUNCATE TABLE
                    fulfillment.delivery_sourcing_jobs,
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    fulfillment.couriers,
                    fulfillment.courier_types,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        now = Instant.now();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        realtime = new RecordingRealtime();
        orders = new RecordingOrders();
        sourcingJobs = new JdbcSourcingJobStore(jdbc);
        JdbcAssignmentStore assignments = new JdbcAssignmentStore(jdbc);
        JdbcDeliveryPlanStore plans = new JdbcDeliveryPlanStore(jdbc);
        jobs = new CourierJobsService(
                new JdbcCourierJobStore(jdbc),
                assignments,
                plans,
                sourcingJobs,
                new JdbcDispatchBranchStore(jdbc),
                orders,
                realtime,
                Clock.fixed(now, ZoneOffset.UTC));

        seedTenancy();
    }

    // ------------------------------------------------------------------ offers

    @Test
    @DisplayName(
            "a courier is shown only their own live offers, for orders still being carried out and plans still open")
    void onlyTheCallersOwnLiveOffersAreListed() {
        UUID mine = seedOffer(COURIER, "CONFIRMED", "SOURCING", now.plusSeconds(120));
        seedOffer(OTHER_COURIER, "CONFIRMED", "SOURCING", now.plusSeconds(120));
        seedOffer(COURIER, "CONFIRMED", "SOURCING", now.minusSeconds(1));
        seedOffer(COURIER, "CANCELLED", "SOURCING", now.plusSeconds(120));
        seedOffer(COURIER, "CONFIRMED", "ASSIGNED", now.plusSeconds(120));

        List<Offer> listed = jobs.openOffers(TENANT, COURIER, now);

        assertThat(listed).extracting(Offer::offerId).containsExactly(mine);
        Offer offer = listed.getFirst();
        assertThat(offer.kitchenReady()).isFalse();
        assertThat(offer.prepaid()).isFalse();
        assertThat(offer.pickup().name()).isEqualTo("Centre");
        assertThat(offer.pickup().latitude()).isEqualTo(BRANCH_LATITUDE);
        assertThat(offer.destinationLabel()).isEqualTo("Chilonzor");

        assertThat(jobs.openOffers(OTHER_TENANT, COURIER, now))
                .as("the same courier id under another tenant names nobody")
                .isEmpty();
        assertThat(jobs.offer(TENANT, OTHER_COURIER, mine, now))
                .as("an offer id is not proof of anything: it matches only the courier it was made to")
                .isEmpty();
    }

    @Test
    @DisplayName("an order is kitchen-ready from READY onwards, and not before")
    void kitchenReadinessFollowsTheOrderStatus() {
        UUID preparing = seedOffer(COURIER, "PREPARING", "SOURCING", now.plusSeconds(120));
        UUID ready = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(180));

        assertThat(jobs.offer(TENANT, COURIER, preparing, now).orElseThrow().kitchenReady())
                .isFalse();
        assertThat(jobs.offer(TENANT, COURIER, ready, now).orElseThrow().kitchenReady())
                .isTrue();
    }

    @Test
    @DisplayName("two taps on one offer make one shipment, and the loser is told it is gone")
    void aDoubleTapIsOneAcceptance() throws Exception {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));

        int taps = 6;
        ExecutorService pool = Executors.newFixedThreadPool(taps);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<UUID>>> results = new ArrayList<>();
        for (int tap = 0; tap < taps; tap++) {
            Callable<Optional<UUID>> accept = () -> {
                go.await();
                return inTransaction(() -> jobs.accept(TENANT, COURIER, offer, now));
            };
            results.add(pool.submit(accept));
        }
        go.countDown();
        List<Optional<UUID>> outcomes = new ArrayList<>();
        for (Future<Optional<UUID>> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes.stream().filter(Optional::isPresent).count())
                .as("exactly one tap wins the single-winner compare-and-set")
                .isEqualTo(1);
        assertThat(count("fulfillment.shipments")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", offer)
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("accepting settles the plan and tells the dispatch board, once the transaction has committed")
    void acceptingSettlesThePlanAndSignalsTheBoard() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));
        UUID plan = planOf(offer);

        List<Integer> publishedBeforeCommit = new ArrayList<>();
        UUID shipment = inTransaction(() -> {
            UUID won = jobs.accept(TENANT, COURIER, offer, now).orElseThrow();
            publishedBeforeCommit.add(realtime.published.size());
            return won;
        });

        assertThat(publishedBeforeCommit)
                .as("a board that re-reads on the strength of the signal must not see the row before the write")
                .containsExactly(0);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.delivery_plans WHERE id = :id")
                        .param("id", plan)
                        .query(String.class)
                        .single())
                .isEqualTo("ASSIGNED");
        assertThat(jobs.job(TENANT, COURIER, shipment).orElseThrow().status()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(realtime.published).hasSize(1).allSatisfy(signal -> {
            assertThat(signal.channel()).isEqualTo(StreamChannel.DISPATCH_BOARD);
            assertThat(signal.tenantId()).isEqualTo(TENANT);
            assertThat(signal.resourceId()).isEqualTo(plan);
        });
    }

    @Test
    @DisplayName(
            "a lapsed offer, another courier's offer and another tenant's offer cannot be accepted, and change nothing")
    void theOfferIsTheCallersAndAlive() {
        UUID lapsed = seedOffer(COURIER, "READY", "SOURCING", now.minusSeconds(1));
        UUID someoneElses = seedOffer(OTHER_COURIER, "READY", "SOURCING", now.plusSeconds(120));
        UUID mine = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));

        assertThat(jobs.accept(TENANT, COURIER, lapsed, now)).isEmpty();
        assertThat(jobs.accept(TENANT, COURIER, someoneElses, now)).isEmpty();
        assertThat(jobs.accept(OTHER_TENANT, COURIER, mine, now)).isEmpty();

        assertThat(count("fulfillment.shipments")).isZero();
        assertThat(realtime.published).isEmpty();
    }

    @Test
    @DisplayName("declining closes the offer as a refusal, not a failure, and pulls a waiting sourcing job to now")
    void decliningWakesAWaitingJob() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));
        UUID waiting = seedSourcingJob(planOf(offer), "PENDING", now.plusSeconds(3600));

        assertThat(jobs.decline(TENANT, COURIER, offer, now)).isTrue();

        assertThat(jdbc.sql("SELECT status, declined_at IS NOT NULL AS declined, failed_at IS NULL AS no_failure "
                                + "FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", offer)
                        .query((row, n) -> row.getString("status") + "/" + row.getBoolean("declined") + "/"
                                + row.getBoolean("no_failure"))
                        .single())
                .isEqualTo("DECLINED/true/true");
        assertThat(jdbc.sql("SELECT due_at FROM fulfillment.delivery_sourcing_jobs WHERE id = :id")
                        .param("id", waiting)
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(realtime.published).hasSize(1);
        assertThat(jobs.decline(TENANT, COURIER, offer, now))
                .as("a second decline finds nothing")
                .isFalse();
    }

    @Test
    @DisplayName("a sourcing job a worker is already holding is left alone when a courier declines")
    void aLeasedJobIsNotMoved() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));
        UUID leased = seedSourcingJob(planOf(offer), "LEASED", now.plusSeconds(3600));

        assertThat(jobs.decline(TENANT, COURIER, offer, now)).isTrue();

        assertThat(jdbc.sql("SELECT due_at > :now FROM fulfillment.delivery_sourcing_jobs WHERE id = :id")
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .param("id", leased)
                        .query(Boolean.class)
                        .single())
                .as("the tick in flight owns what happens next")
                .isTrue();
    }

    @Test
    @DisplayName("another courier cannot decline an offer that is not theirs")
    void aStrangerCannotDecline() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));

        assertThat(jobs.decline(TENANT, OTHER_COURIER, offer, now)).isFalse();
        assertThat(jdbc.sql("SELECT status FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", offer)
                        .query(String.class)
                        .single())
                .isEqualTo("OFFERED");
    }

    // -------------------------------------------------------------- the steps

    @Test
    @DisplayName("a delivery moves forward only, one step at a time, and a replayed step is a no-op that says so")
    void stepsMoveForwardOnly() {
        UUID shipment = acceptedShipment(COURIER);

        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.DELIVERED, now).result())
                .as("handover before pickup")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.PICKUP_PENDING, now)
                        .result())
                .isEqualTo(Result.APPLIED);
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.PICKUP_PENDING, now)
                        .result())
                .as("the same step twice")
                .isEqualTo(Result.ALREADY_THERE);
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.PICKED_UP, now).result())
                .isEqualTo(Result.APPLIED);
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.PICKUP_PENDING, now)
                        .result())
                .as("a delivery never moves backwards")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.DELIVERED, now).result())
                .isEqualTo(Result.APPLIED);

        Job delivered = jobs.job(TENANT, COURIER, shipment).orElseThrow();
        assertThat(delivered.status()).isEqualTo(JobStatus.DELIVERED);
        assertThat(delivered.pickedUpAt()).isNotNull();
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(jobs.jobs(TENANT, COURIER))
                .as("a finished delivery is not what the courier is carrying")
                .isEmpty();
        assertThat(jobs.advance(TENANT, COURIER, shipment, Step.PICKED_UP, now).result())
                .as("and cannot be reopened")
                .isEqualTo(Result.NOT_ALLOWED);
    }

    @Test
    @DisplayName("a shipment of another courier, or of another tenant, matches nothing")
    void aStrangersShipmentMatchesNothing() {
        UUID shipment = acceptedShipment(COURIER);

        assertThat(jobs.advance(TENANT, OTHER_COURIER, shipment, Step.PICKED_UP, now)
                        .result())
                .isEqualTo(Result.NOT_FOUND);
        assertThat(jobs.advance(OTHER_TENANT, COURIER, shipment, Step.PICKED_UP, now)
                        .result())
                .isEqualTo(Result.NOT_FOUND);
        assertThat(jobs.job(TENANT, OTHER_COURIER, shipment)).isEmpty();
        assertThat(jobs.jobs(TENANT, OTHER_COURIER)).isEmpty();
        assertThat(jobs.confirmPayment(TENANT, OTHER_COURIER, shipment, 20_000L, "UZS", now))
                .isEmpty();
        assertThat(jobs.job(TENANT, COURIER, shipment).orElseThrow().status()).isEqualTo(JobStatus.ASSIGNED);
    }

    @Test
    @DisplayName(
            "the payment confirmation is recorded once, on a delivery that has been picked up, and the first moment stands")
    void thePaymentConfirmationIsRecordedOnce() {
        UUID shipment = acceptedShipment(COURIER);
        assertThat(jobs.confirmPayment(TENANT, COURIER, shipment, 20_000L, "UZS", now))
                .as("before pickup there is no cash to count")
                .isEmpty();

        jobs.advance(TENANT, COURIER, shipment, Step.PICKED_UP, now);
        Job first = jobs.confirmPayment(TENANT, COURIER, shipment, 20_000L, "UZS", now)
                .orElseThrow();
        Job replay = jobs.confirmPayment(TENANT, COURIER, shipment, 20_000L, "UZS", now.plusSeconds(60))
                .orElseThrow();

        assertThat(first.paymentConfirmedMinor()).isEqualTo(20_000L);
        assertThat(replay.paymentConfirmedAt()).isEqualTo(first.paymentConfirmedAt());
        assertThat(replay.version()).as("a replay writes nothing").isEqualTo(first.version());
    }

    // ----------------------------------------------------------- measurement

    @Test
    @DisplayName("distance from the branch is metres, and an unplaced branch is unknown rather than zero")
    void distanceFromTheBranch() {
        GeoPoint nearby = new GeoPoint(BRANCH_LATITUDE + 0.001, BRANCH_LONGITUDE);

        OptionalInt metres = jobs.metresFromBranch(TENANT, BRAND, LOCATION, nearby);

        assertThat(metres).isPresent();
        assertThat(metres.getAsInt())
                .isEqualTo(Haversine.metersBetween(new GeoPoint(BRANCH_LATITUDE, BRANCH_LONGITUDE), nearby));
        assertThat(jobs.metresFromBranch(OTHER_TENANT, BRAND, LOCATION, nearby)).isEmpty();

        jdbc.sql("UPDATE tenant.locations SET latitude = NULL, longitude = NULL, coordinate_source = 'NOT_GEOCODED'")
                .update();
        assertThat(jobs.metresFromBranch(TENANT, BRAND, LOCATION, nearby))
                .as("an unplaced branch is not at the origin")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "distance from the door asks ordering for it only for the courier's own shipment, naming the purpose, and hands back a number")
    void distanceFromTheDoorReadsTheAddressOnlyForTheCarrier() {
        UUID shipment = acceptedShipment(COURIER);
        orders.reads.clear();

        OptionalInt metres =
                jobs.metresFromDoor(TENANT, COURIER, shipment, new GeoPoint(DOOR_LATITUDE, DOOR_LONGITUDE));

        assertThat(metres).hasValue(0);
        assertThat(orders.reads).containsExactly("COURIER_PROXIMITY_CHECK");

        orders.reads.clear();
        assertThat(jobs.metresFromDoor(TENANT, OTHER_COURIER, shipment, new GeoPoint(DOOR_LATITUDE, DOOR_LONGITUDE)))
                .isEmpty();
        assertThat(orders.reads)
                .as("nothing is decrypted for a courier who does not carry this shipment")
                .isEmpty();
    }

    @Test
    @DisplayName("the customer's door is read for a live delivery and for nothing else")
    void theDoorIsReadOnlyWhileTheDeliveryIsLive() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));
        UUID shipment = jobs.accept(TENANT, COURIER, offer, now).orElseThrow();
        orders.reads.clear();

        assertThat(jobs.customerLocationOfJob(TENANT, COURIER, shipment, "WHY")).isPresent();
        assertThat(orders.reads).containsExactly("WHY");

        orders.reads.clear();
        assertThat(jobs.customerLocationOfJob(TENANT, OTHER_COURIER, shipment, "WHY"))
                .isEmpty();
        jobs.advance(TENANT, COURIER, shipment, Step.PICKED_UP, now);
        jobs.advance(TENANT, COURIER, shipment, Step.DELIVERED, now);
        assertThat(jobs.customerLocationOfJob(TENANT, COURIER, shipment, "WHY"))
                .as("a courier who has finished with an order has no further business knowing where its customer lives")
                .isEmpty();
        assertThat(orders.reads)
                .as("and nothing was decrypted to find that out")
                .isEmpty();
    }

    @Test
    @DisplayName("an offer's door is read only for the courier it was offered to, and only while it is open")
    void theOffersDoorIsReadOnlyForItsCourier() {
        UUID offer = seedOffer(COURIER, "READY", "SOURCING", now.plusSeconds(120));

        assertThat(jobs.customerLocationOfOffer(TENANT, OTHER_COURIER, offer, now, "WHY"))
                .isEmpty();
        assertThat(orders.reads).isEmpty();
        assertThat(jobs.customerLocationOfOffer(TENANT, COURIER, offer, now.plusSeconds(600), "WHY"))
                .as("a lapsed offer shows nothing")
                .isEmpty();
        assertThat(orders.reads).isEmpty();
        assertThat(jobs.customerLocationOfOffer(TENANT, COURIER, offer, now, "WHY"))
                .isPresent();
        assertThat(orders.reads).containsExactly("WHY");
    }

    // ----------------------------------------------------------------- fixtures

    /** What `@Transactional` gives the service in production, which `new` does not. */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return java.util.Objects.requireNonNull(tx.execute(status -> work.get()));
    }

    private UUID acceptedShipment(UUID courier) {
        UUID offer = seedOffer(courier, "READY", "SOURCING", now.plusSeconds(120));
        UUID shipment = jobs.accept(TENANT, courier, offer, now).orElseThrow();
        realtime.published.clear();
        return shipment;
    }

    private UUID planOf(UUID attemptId) {
        return jdbc.sql("SELECT delivery_plan_id FROM fulfillment.assignment_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(UUID.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private UUID seedSourcingJob(UUID planId, String status, Instant dueAt) {
        UUID id = UUID.randomUUID();
        if ("LEASED".equals(status)) {
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_sourcing_jobs (id, tenant_id, delivery_plan_id, status, due_at,
                        lease_token, leased_until, leased_by)
                    VALUES (:id, :t, :plan, 'LEASED', :due, :token, :until, 'worker')
                    """)
                    .param("id", id)
                    .param("t", TENANT)
                    .param("plan", planId)
                    .param("due", dueAt.atOffset(ZoneOffset.UTC))
                    .param("token", UUID.randomUUID())
                    .param("until", now.plusSeconds(60).atOffset(ZoneOffset.UTC))
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_sourcing_jobs (id, tenant_id, delivery_plan_id, status, due_at)
                    VALUES (:id, :t, :plan, :status, :due)
                    """)
                    .param("id", id)
                    .param("t", TENANT)
                    .param("plan", planId)
                    .param("status", status)
                    .param("due", dueAt.atOffset(ZoneOffset.UTC))
                    .update();
        }
        return id;
    }

    private UUID seedOffer(UUID courier, String orderStatus, String planStatus, Instant expiresAt) {
        UUID orderId = seedOrder(orderStatus);
        UUID planId = seedPlan(orderId, planStatus);
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (id, tenant_id, delivery_plan_id, sequence_number,
                    source_type, courier_id, status, idempotency_key, decision_reason, requested_at, expires_at,
                    version)
                VALUES (:id, :t, :plan, 1, 'INTERNAL', :courier, 'OFFERED', :key, 'INTERNAL_COURIER_AVAILABLE',
                    :requested, :expires, 1)
                """)
                .param("id", attemptId)
                .param("t", TENANT)
                .param("plan", planId)
                .param("courier", courier)
                .param("key", "offer-" + attemptId)
                .param("requested", now.minusSeconds(30).atOffset(ZoneOffset.UTC))
                .param("expires", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
        return attemptId;
    }

    private UUID seedOrder(String status) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', :status,
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, now(), 1, now())
                """)
                .param("id", orderId)
                .param("number", "CJ-" + orderId.toString().substring(0, 8))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .update();
        return orderId;
    }

    private UUID seedPlan(UUID orderId, String status) {
        UUID planId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, customer_delivery_fee_minor, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, source_at,
                    latest_assignment_at, branch_zone, destination_label, version)
                VALUES (:id, :t, :b, :loc, :orderId, :status, 'UZS', 10000, :now, 900,
                    :ready, :ready, :windowEnd, :now, :windowEnd, 'Asia/Tashkent', 'Chilonzor', 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("status", status)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("ready", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("windowEnd", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .update();
        return planId;
    }

    private void seedTenancy() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "courier-jobs-" + tenant)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source, address_line, city)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0,
                    :lat, :lon, 'MERCHANT_PIN', 'Amir Temur 1', 'Tashkent')
                """)
                .param("id", LOCATION)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("lat", BRANCH_LATITUDE)
                .param("lon", BRANCH_LONGITUDE)
                .update();

        UUID courierType = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", courierType).param("t", TENANT).update();
        for (UUID courier : List.of(COURIER, OTHER_COURIER)) {
            jdbc.sql("""
                    INSERT INTO fulfillment.couriers
                        (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                    VALUES (:id, :t, :type, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                    """)
                    .param("id", courier)
                    .param("t", TENANT)
                    .param("type", courierType)
                    .param("subject", "subject-" + courier)
                    .param("reference", "REF-" + courier.toString().substring(0, 8))
                    .update();
        }

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    /** Records every purpose the customer's door was asked for under, and hands back one fixed door. */
    private static final class RecordingOrders implements DeliveryOrderPort {

        final List<String> reads = new CopyOnWriteArrayList<>();

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }

        @Override
        public Optional<CustomerLocation> customerLocation(UUID tenantId, UUID orderId, String purpose) {
            reads.add(purpose);
            return Optional.of(
                    new CustomerLocation(DOOR_LATITUDE, DOOR_LONGITUDE, "Bunyodkor 14", null, null, null, null));
        }
    }

    private static final class RecordingRealtime implements RealtimeSignalPublisher {

        final List<RealtimeSignal> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            published.add(signal);
        }
    }
}
