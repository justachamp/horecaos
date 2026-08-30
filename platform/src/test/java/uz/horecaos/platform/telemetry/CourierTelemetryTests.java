package uz.horecaos.platform.telemetry;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.telemetry.api.CourierShiftPort;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.SettlementCalendarPort;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.FleetView;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService.Reveal;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService.RevealCommand;
import uz.horecaos.platform.telemetry.application.DutySessionService;
import uz.horecaos.platform.telemetry.application.DutySessionService.OpenCommand;
import uz.horecaos.platform.telemetry.application.TelemetryIngestService;
import uz.horecaos.platform.telemetry.application.TelemetryIngestService.IngestOutcome;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.TrackObservation;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.LivePositionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper;
import uz.horecaos.platform.telemetry.infrastructure.startup.TrackRetentionFloorCheck;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Courier duty sessions, telemetry ingest, the reveal, and retention (ADR 0045).
 *
 * <p>Against a real PostgreSQL, because almost everything under test is a
 * property of the database rather than of the Java. Whether a replayed batch
 * creates a second row is a question about a conflict target; whether a stale
 * observation can walk a pin backwards is a predicate on an upsert; whether a
 * track older than the retention window is unreadable is a question about a
 * dropped partition. None of those can be asked of a mock, and every one of them
 * is what goes wrong on a real box six months in.
 */
class CourierTelemetryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID COURIER = UUID.randomUUID();

    /** A Sunday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-23T07:00:00Z");

    /** Chorsu, and a point about a kilometre from it. */
    private static final double CHORSU_LAT = 41.325600;
    private static final double CHORSU_LON = 69.234100;

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcTelemetryStore store;
    private DutySessionService sessions;
    private TelemetryIngestService ingest;
    private CourierPositionQueryService positions;
    private CourierTrackRevealService reveals;
    private TrackRetentionSweeper sweeper;

    private RecordingAuditRecorder audit;
    private RecordingSignalPublisher signals;
    private StubShiftPort shifts;
    private StubConfiguration configuration;
    private MovableClock clock;

    private UUID branch;
    private UUID siblingBranch;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for courier telemetry tests");
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

        jdbc.sql("TRUNCATE TABLE fulfillment.courier_track_summaries, "
                + "fulfillment.courier_location_tracks, fulfillment.courier_positions_live, "
                + "fulfillment.courier_duty_sessions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MovableClock(NOON);
        audit = new RecordingAuditRecorder();
        signals = new RecordingSignalPublisher();
        shifts = new StubShiftPort();
        configuration = new StubConfiguration();

        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek",
                                "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        store = new JdbcTelemetryStore(jdbc);
        sessions = new DutySessionService(store, shifts, audit, clock);
        ingest = new TelemetryIngestService(store, sessions, protection, configuration,
                signals, new AlwaysAllowRateLimiter(), JsonMapper.builder().build(), clock);
        positions = new CourierPositionQueryService(store);
        reveals = new CourierTrackRevealService(store, protection, audit,
                JsonMapper.builder().build(), clock);
        sweeper = new TrackRetentionSweeper(jdbc, store, clock, false, 30);

        seedTenancy();

        // Partitions are DDL and survive the truncate above, and one test in this
        // class deliberately drops every one of them. Recreating today's here
        // keeps each test independent of the order the class happens to run in.
        sweeper.ensurePartitions();
    }

    // ---------------------------------------------------------------- duty sessions

    @Test
    @DisplayName("no ADR 0042 shift means no duty session, and therefore no collection at all")
    void aSessionCannotOpenWithoutAShift() {
        shifts.openShift = Optional.empty();

        Throwable refusal = catchThrowable(this::openSession);

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(((ApiException) refusal).properties()).containsEntry("reason", "NO_OPEN_SHIFT");
        assertThat(openSessions()).isZero();
    }

    @Test
    @DisplayName("an expired self-employment registration refuses the session and says which day")
    void aLapsedRegistrationRefusesCollection() {
        // ADR 0042 owns the record; this ADR only refuses to dispatch and to
        // collect without a valid one. An expired registration turns a compliant
        // arrangement into an undeclared one, and the platform is the only party
        // positioned to notice.
        shifts.openShift = Optional.of(new CourierShiftPort.OpenShift(
                UUID.randomUUID(), branch, BRAND, LocalDate.of(2026, 8, 1)));

        Throwable refusal = catchThrowable(this::openSession);

        assertThat(((ApiException) refusal).properties())
                .containsEntry("reason", "REGISTRATION_LAPSED")
                .containsEntry("registrationValidUntil", "2026-08-01");
        assertThat(openSessions()).isZero();
    }

    @Test
    @DisplayName("the unwired ADR 0042 port refuses rather than collecting with nothing checked")
    void theStandInFailsClosed() {
        // The direction that matters. A stand-in answering yes would collect a
        // named self-employed person's location with no shift behind it and no
        // registration checked, and it would look like a working feature.
        shifts.wired = false;

        Throwable refusal = catchThrowable(this::openSession);

        assertThat(((ApiException) refusal).properties())
                .containsEntry("reason", CourierShiftPort.NOT_WIRED_REASON);
        assertThat(openSessions()).isZero();
    }

    @Test
    @DisplayName("opening twice returns the session already open")
    void aReconnectingHandsetRejoinsRatherThanForking() {
        DutySessionRow first = openSession();
        DutySessionRow second = openSession();

        // A reconnecting handset, a swapped device, and a force-closed app all
        // post this, and answering the second with a conflict would leave a
        // working courier untracked while the dispatcher watches a stale pin.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(openSessions()).isOne();
    }

    @Test
    void openingAndClosingAreAuditedAndTheMapIsNot() {
        DutySessionRow session = openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));
        positions.fleetAt(TENANT, branch, NOON);
        positions.fleetAt(TENANT, branch, NOON);

        sessions.close(TENANT, session.id(), "SIGNED_OFF", ActorRef.user("dispatcher", null),
                "end of shift", "courier.duty.manage", "corr-1");

        // Two acts recorded, and not one row for the map refreshes in between:
        // auditing a five-second map produces more rows than the tenant has
        // orders and buries the reveal that matters.
        assertThat(audit.actionCodes())
                .containsExactly("telemetry.duty_session.opened", "telemetry.duty_session.closed");
    }

    // ---------------------------------------------------------------------- ingest

    @Test
    @DisplayName("an observation with no open duty session is refused with 422 and stored nowhere")
    void collectionAfterSignOffFailsLoudly() {
        Throwable refusal = catchThrowable(() ->
                ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12)));

        assertThat(((ApiException) refusal).errorCode())
                .as("the batch is valid and the state refuses it; a 400 would tell the app to "
                        + "fix a payload that is already right")
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(((ApiException) refusal).properties())
                .containsEntry("reason", "NO_OPEN_DUTY_SESSION");
        assertThat(trackRowCount()).isZero();
        assertThat(livePositionCount()).isZero();
    }

    @Test
    @DisplayName("a break suspends collection, and nothing is stored while it runs")
    void aCourierOnBreakIsNotTracked() {
        DutySessionRow session = openSession();
        sessions.suspend(TENANT, session.id());

        IngestOutcome outcome = ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));

        assertThat(outcome.suspended()).isTrue();
        assertThat(trackRowCount()).isZero();
        assertThat(livePositionCount()).isZero();

        // And resuming starts it again, because ADR 0042 ends the break and this
        // module only follows it.
        sessions.resume(TENANT, session.id());
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));
        assertThat(trackRowCount()).isOne();
    }

    @Test
    @DisplayName("the narrower gate drops a batch from a courier carrying nothing")
    void theAssignmentGateIsImplementedAndNotOnlyDescribed() {
        configuration.gate = CollectionGate.ON_ASSIGNMENT;
        openSession();

        IngestOutcome idle = ingest.ingest(TENANT, COURIER, 0,
                List.of(observation(NOON, CHORSU_LAT, CHORSU_LON, 12)));
        assertThat(idle.observationsAccepted()).isZero();
        assertThat(trackRowCount()).isZero();

        // Carrying an order, the same courier is collected. Both gates work, so a
        // narrower answer from legal is a configuration change and not a redesign.
        IngestOutcome carrying = ingest.ingest(TENANT, COURIER, 1,
                List.of(observation(NOON, CHORSU_LAT, CHORSU_LON, 12)));
        assertThat(carrying.observationsAccepted()).isOne();
        assertThat(trackRowCount()).isOne();
    }

    @Test
    @DisplayName("a replayed batch creates no duplicate rows")
    void ingestIsIdempotentOnTheNaturalKeyRatherThanOnAnIdempotencyRecord() {
        openSession();
        List<TrackObservation> batch = List.of(
                observation(NOON, CHORSU_LAT, CHORSU_LON, 12),
                observation(NOON.plusSeconds(10), CHORSU_LAT + 0.001, CHORSU_LON, 12),
                observation(NOON.plusSeconds(20), CHORSU_LAT + 0.002, CHORSU_LON, 12));

        ingest.ingest(TENANT, COURIER, 1, batch);
        ingest.ingest(TENANT, COURIER, 1, batch);
        ingest.ingest(TENANT, COURIER, 1, batch);

        // One minute, one row, three posts. An idempotency record per beacon would
        // add six rows a minute per courier to the ADR 0031 table for no benefit.
        assertThat(trackRowCount()).isOne();
        assertThat(jdbc.sql("SELECT observation_count FROM fulfillment.courier_location_tracks")
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    @DisplayName("a batch that completes a partly written minute replaces it rather than duplicating")
    void aLaterBatchCompletingAMinuteWins() {
        openSession();
        ingest.ingest(TENANT, COURIER, 1, List.of(observation(NOON, CHORSU_LAT, CHORSU_LON, 12)));
        ingest.ingest(TENANT, COURIER, 1, List.of(
                observation(NOON, CHORSU_LAT, CHORSU_LON, 12),
                observation(NOON.plusSeconds(30), CHORSU_LAT + 0.001, CHORSU_LON, 12)));

        assertThat(trackRowCount()).isOne();
        assertThat(jdbc.sql("SELECT observation_count FROM fulfillment.courier_location_tracks")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("a buffered backlog never walks the pin backwards")
    void anObservationOlderThanTheLiveRowNeverOverwritesIt() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));

        double pinnedLatitude = livePosition().latitude();

        // The device was in a basement and posts its backlog. The readings are
        // genuine and they are older, and replaying them in order would send the
        // courier's dot back across Tashkent while a dispatcher watches.
        ingest.ingest(TENANT, COURIER, 1, List.of(
                observation(NOON.minusSeconds(120), CHORSU_LAT + 0.02, CHORSU_LON, 12),
                observation(NOON.minusSeconds(60), CHORSU_LAT + 0.01, CHORSU_LON, 12)));

        assertThat(livePosition().latitude()).isEqualTo(pinnedLatitude);
        assertThat(livePosition().capturedAt()).isEqualTo(NOON);
        // The backlog is still evidence and is still in the track.
        assertThat(trackRowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a fix worse than a hundred metres is kept as evidence and never drawn")
    void aCoarseFixIsStoredAndNotPinned() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 900));

        assertThat(trackRowCount())
                .as("a coarse fix is still evidence of roughly where somebody was")
                .isOne();
        assertThat(livePositionCount())
                .as("a 900 m accuracy circle rendered as a pin sends a courier to the wrong street")
                .isZero();

        // And the board says the courier exists rather than letting them vanish.
        ingestBatch(observation(NOON.plusSeconds(60), CHORSU_LAT, CHORSU_LON, 40));
        ingest.ingest(TENANT, COURIER, 1,
                List.of(observation(NOON.plusSeconds(120), CHORSU_LAT, CHORSU_LON, 900)));

        FleetView fleet = positions.fleetAt(TENANT, branch, NOON.plusSeconds(120));
        assertThat(fleet.pins()).hasSize(1);
    }

    @Test
    void aMovingCourierSignalsTheMapWithoutCarryingACoordinate() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));

        assertThat(signals.published).hasSize(1);
        RealtimeSignal signal = signals.published.getFirst();
        assertThat(signal.channel()).isEqualTo(StreamChannel.COURIER_POSITIONS);
        assertThat(signal.resourceId()).isEqualTo(COURIER);
        assertThat(signal.scopeKey().canonical()).isEqualTo("LOCATION:" + branch);
    }

    @Test
    void batteryIsOnTheLiveRowAndNeverInTheTrack() {
        openSession();
        ingest.ingest(TENANT, COURIER, 1, List.of(new TrackObservation(
                NOON, CHORSU_LAT, CHORSU_LON, 12, 180.0, 4.2, 14, false)));

        assertThat(livePosition().batteryPercent())
                .as("a dispatcher needs to know a phone will die mid-delivery")
                .isEqualTo(14);

        Reveal reveal = reveals.reveal(revealCommand(NOON.minusSeconds(60), NOON.plusSeconds(600)));
        assertThat(reveal.windows().getFirst().observations().getFirst().keySet())
                .as("a battery history is a work-pattern archive with no operational use")
                .containsExactlyInAnyOrder("t", "lat", "lon", "acc", "hdg", "spd");
    }

    // --------------------------------------------------------------- the dispatcher map

    @Test
    void anotherTenantsFleetIsNotOnThisBranchsMap() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));

        // A courier id arrives from a handset; a lookup matching on it alone would
        // put one tenant's fleet on another tenant's map.
        assertThat(positions.fleetAt(OTHER_TENANT, branch, NOON).pins()).isEmpty();
        assertThat(positions.fleetAt(TENANT, siblingBranch, NOON).pins()).isEmpty();
        assertThat(positions.fleetAt(TENANT, branch, NOON).pins()).hasSize(1);
    }

    @Test
    void aPinOlderThanTenMinutesIsReportedWithoutACoordinate() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));

        FleetView stale = positions.fleetAt(TENANT, branch, NOON.plus(Duration.ofMinutes(20)));

        assertThat(stale.pins()).isEmpty();
        assertThat(stale.withoutPin()).hasSize(1);
        assertThat(stale.withoutPin().getFirst().reason()).isEqualTo("LAST_FIX_TOO_OLD");
    }

    // -------------------------------------------------------------------- the reveal

    @Test
    @DisplayName("every reveal writes an audit entry naming the actor, courier, window, and reason")
    void aRevealIsAlwaysAnswerable() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));
        audit.recorded.clear();

        Reveal reveal = reveals.reveal(revealCommand(NOON.minusSeconds(60), NOON.plusSeconds(600)));

        assertThat(reveal.windows()).hasSize(1);
        assertThat(reveal.windows().getFirst().observations()).hasSize(1);

        AuditFact fact = audit.recorded.getFirst();
        assertThat(fact.actionCode()).isEqualTo("telemetry.courier_track.revealed");
        assertThat(fact.capabilityUsed()).isEqualTo("courier.track.reveal");
        assertThat(fact.actor().subject()).isEqualTo("investigator");
        assertThat(fact.reason()).contains("customer says the order never arrived");
        assertThat(fact.changeDocument())
                .containsEntry("courierId", COURIER.toString())
                .containsKeys("windowFrom", "windowTo", "windowsRevealed");
    }

    @Test
    @DisplayName("a reveal with no stated purpose is refused rather than defaulted")
    void aPurposeIsNotOptional() {
        openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));
        audit.recorded.clear();

        Throwable blank = catchThrowable(() -> reveals.reveal(new RevealCommand(
                TENANT, BRAND, branch, COURIER, NOON.minusSeconds(60), NOON.plusSeconds(600),
                "   ", ActorRef.user("investigator", null), "corr-2")));
        Throwable tooShort = catchThrowable(() -> reveals.reveal(new RevealCommand(
                TENANT, BRAND, branch, COURIER, NOON.minusSeconds(60), NOON.plusSeconds(600),
                "checking", ActorRef.user("investigator", null), "corr-3")));

        assertThat(((ApiException) blank).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(((ApiException) tooShort).errorCode())
                .as("\"checking\" is not a purpose; the entry is only worth writing if the "
                        + "sentence in it answers, months later, why somebody looked")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(audit.recorded)
                .as("a refused reveal decrypted nothing, so there is nothing to record")
                .isEmpty();
    }

    @Test
    void aRevealIsBoundedAndNeverJustThisCouriersTrack() {
        openSession();

        Throwable unbounded = catchThrowable(() -> reveals.reveal(new RevealCommand(
                TENANT, BRAND, branch, COURIER, NOON.minus(Duration.ofDays(400)), NOON,
                "an unbounded request for a movement history", ActorRef.user("x", null), "corr")));

        assertThat(((ApiException) unbounded).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ------------------------------------------------------------------- retention

    @Test
    @DisplayName("the live row is deleted an hour after sign-off, and the track is not")
    void thePinExpiresWithTheGraceThatPaysForStoringItInCleartext() {
        DutySessionRow session = openSession();
        ingestBatch(observation(NOON, CHORSU_LAT, CHORSU_LON, 12));
        sessions.close(TENANT, session.id(), "SIGNED_OFF", ActorRef.user("dispatcher", null),
                "end of shift", "courier.duty.manage", "corr-4");

        // Half an hour later a dispatcher is still on the phone about the last
        // delivery, and the pin is still there.
        clock.moveTo(NOON.plusSeconds(1800));
        assertThat(sweeper.expireLivePositions()).isZero();
        assertThat(livePositionCount()).isOne();

        clock.moveTo(NOON.plusSeconds(3700));
        assertThat(sweeper.expireLivePositions()).isOne();
        assertThat(livePositionCount()).isZero();
        assertThat(trackRowCount())
                .as("the track has its own, longer window and its own mechanism")
                .isOne();
    }

    @Test
    @DisplayName("a track past the window is unreadable while its summary survives")
    void retentionIsADroppedPartitionRatherThanASweep() {
        Instant longAgo = aDayEveryRetentionWindowHasPassed();
        clock.moveTo(longAgo);
        sweeper.ensurePartitions();

        DutySessionRow session = openSession();
        ingestBatch(observation(longAgo, CHORSU_LAT, CHORSU_LON, 12));
        ingest.ingest(TENANT, COURIER, 1,
                List.of(observation(longAgo.plusSeconds(90), CHORSU_LAT + 0.01, CHORSU_LON, 12)));

        // What ADR 0042 will settle against once the track is gone: a distance and
        // two times, neither of which is personal data.
        var aggregate = store.aggregateForSession(TENANT, session.id()).orElseThrow();
        store.insertSummary(new JdbcTelemetryStore.TrackSummaryRow(
                UUID.randomUUID(), TENANT, COURIER, session.id(), null,
                aggregate.distanceMeters(), aggregate.observationCount(),
                aggregate.firstObservedAt(), aggregate.lastObservedAt(), null, null, longAgo));

        List<String> dropped = sweeper.dropExpiredPartitions();

        assertThat(dropped).isNotEmpty();
        assertThat(trackRowCount())
                .as("nothing reads a track past the dispute window, and holding it anyway is a "
                        + "movement archive of identified people")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_track_summaries")
                .query(Long.class).single())
                .as("the figure a settlement dispute is argued with outlives the path it came from")
                .isOne();
    }

    @Test
    void reportOnlyRetentionSaysWhatItWouldDeleteAndDeletesNothing() {
        TrackRetentionSweeper reportOnly = new TrackRetentionSweeper(jdbc, store, clock, true, 30);

        Instant longAgo = aDayEveryRetentionWindowHasPassed();
        clock.moveTo(longAgo);
        reportOnly.ensurePartitions();

        DutySessionRow session = openSession();
        ingestBatch(observation(longAgo, CHORSU_LAT, CHORSU_LON, 12));
        sessions.close(TENANT, session.id(), "SIGNED_OFF", ActorRef.user("dispatcher", null),
                "end of shift", "courier.duty.manage", "corr-5");

        assertThat(reportOnly.dropExpiredPartitions()).isNotEmpty();
        assertThat(reportOnly.expireLivePositions()).isZero();
        assertThat(trackRowCount()).isOne();
        assertThat(livePositionCount()).isOne();
    }

    @Test
    void theDefaultPartitionIsNeverDropped() {
        // It cannot be: dropping it would take every future misrouted row with it.
        // Rows landing there are a symptom to report, not data to delete.
        clock.moveTo(NOON.plus(Duration.ofDays(400)));
        sweeper.ensurePartitions();

        assertThat(sweeper.dropExpiredPartitions())
                .noneMatch(table -> table.endsWith("_default"));
        assertThat(sweeper.defaultPartitionRowCount()).isZero();
    }

    // -------------------------------------------------- the startup check on the floor

    @Test
    @DisplayName("a tenant that shortens retention below the floor refuses a production start")
    void theFloorIsEnforcedAgainstEveryStoredValueAndNotOnlyTheDefault() {
        // The realistic way this breaks. Nobody edits the code default; a tenant
        // is told its data footprint is too large and sets ten days, which is
        // under the 14-day floor its own settlement calendar implies.
        storeRetention(10);

        Throwable refusal = catchThrowable(() -> productionCheck().check());

        assertThat(refusal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TENANT " + TENANT)
                .hasMessageContaining("below the 14-day floor")
                .hasMessageContaining("settlement_period_days + statement_dispute_days");
    }

    @Test
    void aStoredValueThatClearsTheFloorStartsCleanly() {
        storeRetention(21);

        assertThat(productionCheck().check())
                .as("the code default and the tenant's value are both checked")
                .hasSize(2)
                .allMatch(verdict -> !verdict.refusesStartup());
    }

    @Test
    @DisplayName("report-only reports the breach and does not refuse the start")
    void reportOnlyIsAnOptOutForOneDeploymentRatherThanASilentDefault() {
        storeRetention(10);

        TrackRetentionFloorCheck reportOnly = new TrackRetentionFloorCheck(
                jdbc, new PilotCalendar(), productionEnvironment(), "REPORT_ONLY");

        assertThat(reportOnly.check())
                .anyMatch(verdict -> verdict.refusesStartup());
    }

    @Test
    @DisplayName("a partition is shared, so it is dropped at the longest window anybody configured")
    void aTenantsLongerRetentionGovernsTheSharedPartition() {
        // The startup check refuses anything below the floor, so a stored value
        // is only ever longer than the platform default. Dropping at the default
        // would delete evidence that tenant is required to hold, and a shared
        // partition cannot be dropped per tenant.
        assertThat(sweeper.effectiveRetentionDays()).isEqualTo(30);

        // Forty-five days old by the clock that decides — V0075's function reads
        // the database's current_date and not this class's movable one, so the
        // day is asked for rather than assumed.
        LocalDate day = jdbc.sql("SELECT current_date - 45").query(LocalDate.class).single();
        String partition = "courier_location_tracks_"
                + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        sweeper.ensurePartition(day);

        storeRetention(60);
        assertThat(sweeper.effectiveRetentionDays()).isEqualTo(60);
        assertThat(sweeper.dropExpiredPartitions())
                .as("day 45 is past the platform's 30 and inside the tenant's 60")
                .doesNotContain(partition);

        // And the rule is the tenant's value doing the work, not the day being
        // young: with the value withdrawn the same partition is past the window.
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = :keyCode")
                .param("keyCode", TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE)
                .update();
        assertThat(sweeper.effectiveRetentionDays()).isEqualTo(30);
        assertThat(sweeper.dropExpiredPartitions()).contains(partition);
    }

    /**
     * A day the database considers long past every retention window there is.
     *
     * <p>V0075 moved the expiry decision out of this class and into a function
     * owned by the migration role, and the cutoff there is the database's own
     * {@code current_date}. That is deliberate: a retention window that an
     * injected clock can move is not a retention control, and the application
     * must not be able to delete a courier's movement history a day early by
     * being handed a different {@link Clock}.
     *
     * <p>So a test that wants a partition genuinely dropped adopts the clock of
     * the thing under test. The fixture's clock is still the test's clock — it is
     * just set from the one that governs the behaviour, asked for here rather
     * than read off a wall clock, so this stays true however long from now the
     * suite runs.
     */
    private Instant aDayEveryRetentionWindowHasPassed() {
        return jdbc.sql("SELECT current_date - 400")
                .query(LocalDate.class).single()
                .atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private void storeRetention(int days) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id,
                    value_type, integer_value, set_by)
                VALUES (:id, :keyCode, 'TENANT', :tenantId, 'INTEGER', :days, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE)
                .param("tenantId", TENANT)
                .param("days", (long) days)
                .update();
    }

    private TrackRetentionFloorCheck productionCheck() {
        return new TrackRetentionFloorCheck(jdbc, new PilotCalendar(), productionEnvironment(), "AUTO");
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }

    /** ADR 0045's stated pilot calendar: a 7-day period and a 7-day dispute window. */
    private static final class PilotCalendar implements SettlementCalendarPort {

        @Override
        public int settlementPeriodDays() {
            return 7;
        }

        @Override
        public int statementDisputeDays() {
            return 7;
        }
    }

    // -------------------------------------------------------------------- fixtures

    private DutySessionRow openSession() {
        return sessions.open(new OpenCommand(TENANT, COURIER, branch, "device-1",
                CollectionGate.ON_DUTY, ActorRef.user("dispatcher", null),
                "the courier signed on", "courier.duty.manage", "corr-open"));
    }

    private IngestOutcome ingestBatch(TrackObservation... observations) {
        return ingest.ingest(TENANT, COURIER, 1, List.of(observations));
    }

    private static TrackObservation observation(Instant at, double latitude, double longitude,
            double accuracyMeters) {
        return new TrackObservation(at, latitude, longitude, accuracyMeters, null, null, null, null);
    }

    private RevealCommand revealCommand(Instant from, Instant to) {
        return new RevealCommand(TENANT, BRAND, branch, COURIER, from, to,
                "a customer says the order never arrived and the courier says it was handed over",
                ActorRef.user("investigator", null), "corr-reveal");
    }

    private LivePositionRow livePosition() {
        return store.livePosition(TENANT, COURIER).orElseThrow();
    }

    private long livePositionCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_positions_live")
                .query(Long.class).single();
    }

    private long trackRowCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_location_tracks")
                .query(Long.class).single();
    }

    private long openSessions() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_duty_sessions WHERE ended_at IS NULL")
                .query(Long.class).single();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'telemetry-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = insertLocation("CENTRE", "centre");
        siblingBranch = insertLocation("NORTH", "north");

        shifts.openShift = Optional.of(new CourierShiftPort.OpenShift(
                UUID.randomUUID(), branch, BRAND, LocalDate.of(2027, 1, 1)));
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

    private static final class StubShiftPort implements CourierShiftPort {

        private Optional<OpenShift> openShift = Optional.empty();
        private boolean wired = true;

        @Override
        public Optional<OpenShift> openShift(UUID tenantId, UUID courierId, UUID locationId) {
            return openShift;
        }

        @Override
        public boolean isWired() {
            return wired;
        }
    }

    /** Resolves the gate without a scope chain; ADR 0030's precedence is tested elsewhere. */
    private static final class StubConfiguration implements ConfigurationResolver {

        private CollectionGate gate = CollectionGate.ON_DUTY;

        @SuppressWarnings("unchecked")
        @Override
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            if (key.code().equals(TelemetryConfigurationKeys.COLLECTION_GATE_CODE)) {
                return new Resolved<>((T) gate.name(), trace());
            }
            return new Resolved<>(key.defaultValue(), trace());
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return trace();
        }

        private static ResolutionTrace trace() {
            return new ResolutionTrace(TelemetryConfigurationKeys.COLLECTION_GATE_CODE,
                    ResolutionTrace.Source.CODE_DEFAULT, null, List.of());
        }
    }

    private static final class AlwaysAllowRateLimiter implements RateLimiter {

        @Override
        public Decision check(Key key, Policy policy) {
            // The cadence limit is a property of the limiter, tested where it
            // lives; letting it through here keeps these tests about storage.
            return Decision.allowed(policy.permits());
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> recorded = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            recorded.add(fact);
        }

        List<String> actionCodes() {
            return recorded.stream().map(AuditFact::actionCode).toList();
        }
    }

    private static final class RecordingSignalPublisher implements RealtimeSignalPublisher {

        private final List<RealtimeSignal> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            published.add(signal);
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void moveTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
