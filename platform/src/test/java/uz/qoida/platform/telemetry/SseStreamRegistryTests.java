package uz.qoida.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.telemetry.api.RealtimeSignal;
import uz.qoida.platform.telemetry.api.RealtimeSignal.Subscription;
import uz.qoida.platform.telemetry.api.ScopeKey;
import uz.qoida.platform.telemetry.api.StreamChannel;
import uz.qoida.platform.telemetry.infrastructure.realtime.SnapshotSource;
import uz.qoida.platform.telemetry.infrastructure.realtime.SseStreamRegistry;
import uz.qoida.platform.telemetry.infrastructure.realtime.SseStreamRegistry.Connection;
import uz.qoida.platform.telemetry.infrastructure.realtime.SseStreamRegistry.StreamCapReachedException;
import uz.qoida.platform.telemetry.infrastructure.realtime.StreamSink;

/**
 * The transport's behaviour, driven instant by instant (ADR 0045).
 *
 * <p>No servlet, no socket, and no sleeping. Everything ADR 0045 decided about
 * the stream is a question of what is written and when — forty bulk assignments
 * producing one frame per coalescing window, a reconnect receiving a resync
 * rather than a replay, a heartbeat keeping a proxy from idling the connection
 * closed, and a stream ending when its token expires or its grants change. A test
 * that had to sleep to ask any of those would be slow and flaky in the same
 * change, which is why {@code tick} takes the time rather than reading a clock.
 */
class SseStreamRegistryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final Instant NOON = Instant.parse("2026-08-23T07:00:00Z");
    private static final String DISPATCHER = "keycloak-subject-dispatcher";

    private SseStreamRegistry registry;
    private RecordingSink sink;
    private MovableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(NOON);
        registry = newRegistry(List.of(new FleetSnapshotSource()));
        sink = new RecordingSink();
    }

    private SseStreamRegistry newRegistry(List<SnapshotSource> sources) {
        return new SseStreamRegistry(new ObjectMapper(), clock, new SimpleMeterRegistry(), sources);
    }

    // ------------------------------------------------------------------- coalescing

    @Test
    @DisplayName("forty bulk assignments produce one frame per coalescing window")
    void aBulkAssignmentIsOneFrameAndNotForty() {
        open(Set.of(queueSubscription()));

        // A documented Delever operation: forty orders assigned at once, which is
        // forty domain events. Uncoalesced that is forty frames and forty
        // authenticated re-reads per connected operator, against a primary that
        // has no read replica and also serves reporting.
        for (int order = 0; order < 40; order++) {
            registry.onSignal(orderQueueSignal(NOON.plusMillis(order * 2L)));
        }

        // Nothing yet: the 250 ms cap has not elapsed.
        registry.tick(NOON.plusMillis(100));
        assertThat(sink.frames).isEmpty();

        registry.tick(NOON.plusMillis(300));
        assertThat(sink.eventsNamed("signal"))
                .as("one frame carrying the newest state of the scope, not forty")
                .hasSize(1);

        // And the frame that survived is the last one, because a client re-reads
        // the scope and the oldest of forty would tell it to re-read for a version
        // it has already passed.
        assertThat(sink.frames.getLast().data()).contains("\"version\":40");
    }

    @Test
    @DisplayName("a steady stream of changes still emits once per cap rather than being starved")
    void aBusyScopeIsNotDeferredIndefinitely() {
        open(Set.of(queueSubscription()));

        // The bug this guards: restarting the coalescing window on every
        // replacement means a branch that changes something every 100 ms never
        // emits at all, and the screen it feeds is permanently one change behind
        // with no error anywhere. A replacing signal keeps the original instant,
        // so the window closes on schedule however busy the scope is.
        for (int step = 0; step < 6; step++) {
            clock.moveTo(NOON.plusMillis(step * 100L));
            registry.onSignal(orderQueueSignal(clock.instant()));
            registry.tick(clock.instant());
        }
        assertThat(sink.eventsNamed("signal"))
                .as("the first window closed at 300 ms rather than being pushed out by each change")
                .hasSize(1);

        // And the changes since then leave on the next window rather than waiting
        // for the scope to go quiet.
        registry.tick(NOON.plusMillis(700));
        assertThat(sink.eventsNamed("signal")).hasSize(2);
    }

    @Test
    void aSignalForAnotherTenantIsNotDelivered() {
        open(Set.of(queueSubscription()));

        registry.onSignal(RealtimeSignal.of(UUID.randomUUID(), StreamChannel.ORDER_QUEUE,
                ScopeKey.location(BRANCH), "ORDER", UUID.randomUUID(), 1L, NOON));
        registry.tick(NOON.plusSeconds(1));

        assertThat(sink.frames)
                .as("the scope key is a routing key and never an authorization decision")
                .isEmpty();
    }

    @Test
    void aSignalForAnUnsubscribedChannelIsNotDelivered() {
        open(Set.of(queueSubscription()));

        registry.onSignal(RealtimeSignal.of(TENANT, StreamChannel.STOP_LIST,
                ScopeKey.location(BRANCH), "OFFERING", UUID.randomUUID(), 1L, NOON));
        registry.tick(NOON.plusSeconds(1));

        assertThat(sink.frames).isEmpty();
    }

    // --------------------------------------------------------------------- snapshots

    @Test
    @DisplayName("a registered snapshot channel carries its payload inline")
    void theCourierMapArrivesAsASnapshotRatherThanAsNFetches() {
        open(Set.of(new Subscription(StreamChannel.COURIER_POSITIONS, ScopeKey.location(BRANCH))));

        registry.onSignal(RealtimeSignal.of(TENANT, StreamChannel.COURIER_POSITIONS,
                ScopeKey.location(BRANCH), "COURIER", UUID.randomUUID(), null, NOON));
        registry.tick(NOON.plusSeconds(6));

        assertThat(sink.eventsNamed("snapshot")).hasSize(1);
        assertThat(sink.frames.getLast().data()).contains("\"pins\"");
    }

    @Test
    @DisplayName("a snapshot channel with no source degrades to a signal rather than a gap")
    void aMissingSnapshotSourceTellsTheClientToReRead() {
        // COUNTERS belongs to ordering, which supplies its own source. Until it
        // does, the honest behaviour is the polling path the surface must have
        // anyway — not silence that reads as a wall-board that stopped counting.
        registry = newRegistry(List.of());
        open(Set.of(new Subscription(StreamChannel.COUNTERS, ScopeKey.location(BRANCH))));

        registry.onSignal(RealtimeSignal.of(TENANT, StreamChannel.COUNTERS,
                ScopeKey.location(BRANCH), "COUNTER", null, null, NOON));
        registry.tick(NOON.plusSeconds(3));

        assertThat(sink.eventsNamed("signal")).hasSize(1);
    }

    // ----------------------------------------------------------- reconnect, heartbeat

    @Test
    @DisplayName("a reconnect with Last-Event-Id gets a resync, never a replay")
    void thereIsNoReplayBuffer() {
        registry.open(TENANT, DISPATCHER, Set.of(queueSubscription()), sink,
                NOON.plusSeconds(600), "01J8ZQ-something-the-client-last-saw");

        // A buffer would be server-side per-client state, which ADR 0033 spent a
        // section refusing — and three stale frames delivered in order to a
        // five-second map are worse than one instruction to re-read.
        assertThat(sink.eventsNamed("resync")).hasSize(1);
        assertThat(sink.frames.getFirst().data()).contains("NO_REPLAY_BUFFER");
    }

    @Test
    void aQuietConnectionIsHeartbeatedSoAProxyDoesNotIdleItClosed() {
        open(Set.of(queueSubscription()));

        registry.tick(NOON.plusSeconds(10));
        assertThat(sink.heartbeats).isZero();

        registry.tick(NOON.plusSeconds(16));
        assertThat(sink.heartbeats).isOne();

        // And a connection that has just been written to does not also need one.
        registry.onSignal(orderQueueSignal(NOON.plusSeconds(20)));
        registry.tick(NOON.plusSeconds(25));
        registry.tick(NOON.plusSeconds(30));
        assertThat(sink.heartbeats).isOne();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    @DisplayName("a stream closes when its access token expires")
    void aStreamNeverOutlivesTheTokenItWasOpenedWith() {
        registry.open(TENANT, DISPATCHER, Set.of(queueSubscription()), sink,
                NOON.plusSeconds(300), null);

        registry.tick(NOON.plusSeconds(299));
        assertThat(registry.openStreams()).isOne();

        // The hole this closes: a wall board opened at 08:00 on a five-minute
        // token, still receiving a kitchen's queue at 22:00.
        registry.tick(NOON.plusSeconds(301));
        assertThat(registry.openStreams()).isZero();
        assertThat(sink.frames.getLast().data()).contains("TOKEN_EXPIRED");
        assertThat(sink.completed).isTrue();
    }

    @Test
    @DisplayName("a grants change closes the principal's streams and nobody else's")
    void arevokedScopeStopsTheStreamNow() {
        open(Set.of(queueSubscription()));

        RecordingSink otherPerson = new RecordingSink();
        registry.open(TENANT, "another-subject", Set.of(queueSubscription()),
                otherPerson, NOON.plusSeconds(600), null);

        int closed = registry.closeForPrincipal(DISPATCHER, "GRANTS_CHANGED");

        assertThat(closed).isOne();
        assertThat(registry.openStreams()).isOne();
        assertThat(sink.frames.getLast().data()).contains("GRANTS_CHANGED");
        // The client is told to come back jittered, because ADR 0034 has no
        // rolling deploy and every stream on the box dies together.
        assertThat(sink.frames.getLast().data()).contains("reconnectAfterSecondsMax");
        assertThat(otherPerson.completed).isFalse();
    }

    // ------------------------------------------------------------------------ the cap

    @Test
    @DisplayName("the five-hundredth stream is the last one this replica takes")
    void theCapRefusesRatherThanDegradingSilently() {
        for (int stream = 0; stream < SseStreamRegistry.MAXIMUM_STREAMS; stream++) {
            openFor(UUID.randomUUID(), "subject-" + stream);
        }
        assertThat(registry.openStreams()).isEqualTo(SseStreamRegistry.MAXIMUM_STREAMS);

        // A cap that is hit is a signal to revisit the transport, not to raise the
        // cap; the client is told to poll, which is the path it must have anyway.
        assertThat(catchThrowable(() -> openFor(UUID.randomUUID(), "one-too-many")))
                .isInstanceOf(StreamCapReachedException.class)
                .hasMessageContaining("already holds 500 streams")
                .hasMessageContaining("Poll instead");
    }

    @Test
    @DisplayName("one tenant cannot empty the replica's pool")
    void aGreedyTenantStopsAtTheReserveRatherThanAtTheCap() {
        int lendable = SseStreamRegistry.MAXIMUM_STREAMS
                - SseStreamRegistry.RESERVED_FOR_OTHER_TENANTS;
        for (int stream = 0; stream < lendable; stream++) {
            openFor(TENANT, "wall-board-" + stream);
        }

        // Spare capacity is lent out, so nothing refused this tenant on the way up.
        assertThat(registry.streamsHeldBy(TENANT)).isEqualTo(lendable);

        // The bug this guards: a chain opening a wall board in each of four
        // hundred branches used to leave every other tenant on the box with no
        // realtime push at all, and nothing on either side said why.
        assertThat(catchThrowable(() -> openFor(TENANT, "four-hundred-and-one")))
                .isInstanceOf(StreamCapReachedException.class)
                .hasMessageContaining("already holds 400 of this replica's 500 streams")
                .hasMessageContaining("kept for other tenants");
        assertThat(registry.openStreams()).isEqualTo(lendable);
    }

    @Test
    @DisplayName("a tenant under its guarantee still connects to a crowded replica")
    void theReserveIsWhatMakesTheGuaranteeAGuarantee() {
        UUID neighbour = UUID.randomUUID();
        for (int stream = 0; stream < SseStreamRegistry.MAXIMUM_STREAMS
                - SseStreamRegistry.RESERVED_FOR_OTHER_TENANTS; stream++) {
            openFor(neighbour, "neighbour-" + stream);
        }

        for (int stream = 0; stream < SseStreamRegistry.TENANT_GUARANTEED_STREAMS; stream++) {
            openFor(TENANT, "dispatcher-" + stream);
        }
        assertThat(registry.streamsHeldBy(TENANT))
                .isEqualTo(SseStreamRegistry.TENANT_GUARANTEED_STREAMS);

        // Past its own guarantee this tenant is refused too, and the slot it did
        // not take is the one the third tenant on the box gets.
        assertThat(catchThrowable(() -> openFor(TENANT, "fifty-one")))
                .isInstanceOf(StreamCapReachedException.class);
        UUID third = UUID.randomUUID();
        openFor(third, "third-tenant");
        assertThat(registry.streamsHeldBy(third)).isOne();
    }

    @Test
    void closingReleasesTheSlot() {
        Connection connection = open(Set.of(queueSubscription()));
        assertThat(registry.openStreams()).isOne();

        registry.close(connection.id());

        assertThat(registry.openStreams())
                .as("a connection left in the map after its socket died is a leak "
                        + "that looks like headroom until the cap refuses a real client")
                .isZero();
        assertThat(sink.completed).isTrue();
    }

    @Test
    void aClientThatWentAwayMidWriteIsDroppedRatherThanRetried() {
        RecordingSink broken = new RecordingSink();
        broken.failOnSend = true;
        registry.open(TENANT, DISPATCHER, Set.of(queueSubscription()), broken,
                NOON.plusSeconds(600), null);

        registry.onSignal(orderQueueSignal(NOON));
        registry.tick(NOON.plusSeconds(1));

        // A browser tab closed, a wall display rebooted, a laptop lid shut. All
        // ordinary, none an incident, and every one of them reconnects.
        assertThat(registry.openStreams()).isZero();
    }

    // ---------------------------------------------------------------------- fixtures

    private Connection open(Set<Subscription> subscriptions) {
        return registry.open(TENANT, DISPATCHER, subscriptions, sink, NOON.plusSeconds(600), null);
    }

    private Connection openFor(UUID tenantId, String subject) {
        return registry.open(tenantId, subject, Set.of(queueSubscription()),
                new RecordingSink(), NOON.plusSeconds(600), null);
    }

    private static Subscription queueSubscription() {
        return new Subscription(StreamChannel.ORDER_QUEUE, ScopeKey.location(BRANCH));
    }

    private static RealtimeSignal orderQueueSignal(Instant at) {
        return RealtimeSignal.of(TENANT, StreamChannel.ORDER_QUEUE, ScopeKey.location(BRANCH),
                "ORDER", UUID.randomUUID(), (long) (at.toEpochMilli() - NOON.toEpochMilli()) / 2 + 1, at);
    }

    private static final class FleetSnapshotSource implements SnapshotSource {

        @Override
        public StreamChannel channel() {
            return StreamChannel.COURIER_POSITIONS;
        }

        @Override
        public Optional<Object> snapshot(UUID tenantId, ScopeKey scopeKey) {
            return Optional.of(Map.of("pins", List.of(Map.of("courierId", UUID.randomUUID().toString()))));
        }
    }

    private static final class RecordingSink implements StreamSink {

        private final List<Frame> frames = new ArrayList<>();
        private int heartbeats;
        private boolean completed;
        private boolean failOnSend;

        @Override
        public void send(String eventName, String id, String data) {
            if (failOnSend) {
                throw new IllegalStateException("the client went away");
            }
            frames.add(new Frame(eventName, id, data));
        }

        @Override
        public void heartbeat() {
            heartbeats++;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable failure) {
            completed = true;
        }

        List<Frame> eventsNamed(String eventName) {
            return frames.stream().filter(frame -> frame.eventName().equals(eventName)).toList();
        }
    }

    private record Frame(String eventName, String id, String data) {
    }

    /** A clock a test moves, so nothing here has to sleep to observe a window close. */
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
