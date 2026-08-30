package uz.qoida.platform.telemetry.infrastructure.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.telemetry.api.RealtimeSignal;
import uz.qoida.platform.telemetry.api.RealtimeSignal.Subscription;
import uz.qoida.platform.telemetry.api.ScopeKey;
import uz.qoida.platform.telemetry.api.StreamChannel;

/**
 * Every open stream this replica holds, and the rules that decide what each of
 * them is sent (ADR 0045).
 *
 * <p><strong>Process-local, and that is the design.</strong> A stream dies with
 * its replica, which is precisely why a client must reconnect and resync, and why
 * there is no subscription table. ADR 0033 spent a section refusing server-side
 * per-client state and this does not reintroduce it: nothing here survives a
 * restart, and nothing here is authoritative about anything.
 *
 * <p><strong>Coalescing is a correctness budget, not an optimisation.</strong>
 * One signal fanned out to fifty connected clients is fifty authenticated reads
 * against a primary that has no read replica and also serves ADR 0043's
 * reporting. A bulk assignment of forty orders — a documented Delever operation —
 * emits forty domain events, and uncoalesced that is forty frames and forty
 * fetches per operator. Each channel's cadence cap bounds that amplification
 * without removing it.
 *
 * <p><strong>Two things close a stream, and both are authorization.</strong> The
 * access token expiring, because a connection held open all shift on a token that
 * expired in five minutes is an authorization hole that looks like a working
 * feature. And a grants change, because a supervisor whose location scope is
 * revoked must stop watching that kitchen's queue now rather than at end of
 * shift.
 *
 * <p>The 500-stream cap is a signal to revisit the transport rather than a number
 * to raise quietly, and the 350 warning is deliberately a dashboard threshold and
 * not a page: ADR 0034's night-alert budget is three, and this is not one of them.
 *
 * <p><strong>The cap is shared, so it is also divided.</strong> A single pool of
 * 500 on a multi-tenant replica is a pool one tenant can empty — a chain opening a
 * wall board in each of four hundred branches leaves every other tenant on the box
 * with no realtime push at all, and nothing on either side says why. So the last
 * {@link #RESERVED_FOR_OTHER_TENANTS} slots are reachable only by a tenant still
 * under {@link #TENANT_GUARANTEED_STREAMS}. Spare capacity is lent out rather than
 * held idle — below the reserve any tenant may take as much of the pool as it
 * likes — but no tenant can hold more than
 * {@code MAXIMUM_STREAMS - RESERVED_FOR_OTHER_TENANTS}, which is what makes the
 * guarantee a guarantee. A fixed {@code cap / tenant count} share was rejected for
 * the opposite failure: a replica serving one tenant would refuse that tenant at a
 * fraction of a capacity nobody else wants.
 */
@Component
public class SseStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseStreamRegistry.class);

    /** Above this, connect is refused with ADR 0031's RATE_LIMIT_EXCEEDED. */
    public static final int MAXIMUM_STREAMS = 500;

    /** Visible on the operations dashboard. Deliberately not a page. */
    public static final int WARNING_THRESHOLD = 350;

    /**
     * What one tenant gets whatever its neighbours are doing.
     *
     * <p>Fifty is a dispatcher screen, a kitchen display in each of a dozen
     * branches, and room for the reconnect herd a deploy produces — enough that a
     * tenant inside its guarantee never notices the divide exists.
     */
    public static final int TENANT_GUARANTEED_STREAMS = 50;

    /**
     * Slots a tenant past its guarantee may not take.
     *
     * <p>Two guarantees' worth, so a maximally greedy neighbour still leaves the
     * floor standing for two more tenants rather than for a rounding error.
     */
    public static final int RESERVED_FOR_OTHER_TENANTS = 100;

    /** Long enough to be cheap, short enough that no ordinary proxy idles it out. */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<StreamChannel, SnapshotSource> snapshotSources = new ConcurrentHashMap<>();
    private final AtomicLong framesSent = new AtomicLong();

    private final ObjectMapper json;
    private final Clock clock;
    private final Counter refusedConnects;
    private final Counter refusedTenantShare;
    private final Counter coalescedSignals;

    public SseStreamRegistry(ObjectMapper json, Clock clock, MeterRegistry meters,
            List<SnapshotSource> sources) {
        this.json = json;
        this.clock = clock;
        sources.forEach(source -> snapshotSources.put(source.channel(), source));

        Gauge.builder("qoida.realtime.streams.open", connections, Map::size)
                .description("ADR 0045 open Server-Sent Event streams held by this replica")
                .register(meters);
        Gauge.builder("qoida.realtime.streams.headroom", connections,
                        open -> (double) MAXIMUM_STREAMS - open.size())
                .description("Streams remaining before the ADR 0045 cap refuses connects")
                .register(meters);
        this.refusedConnects = Counter.builder("qoida.realtime.streams.refused")
                .description("Connects refused because the ADR 0045 stream cap was reached")
                .register(meters);
        // A subset of the above rather than a separate outcome: a refusal is a
        // refusal on the dashboard, and this says how many of them were the
        // replica protecting somebody else's tenant rather than being full.
        this.refusedTenantShare = Counter.builder("qoida.realtime.streams.refused.tenant.share")
                .description("Refused connects that were a tenant past its ADR 0045 share, "
                        + "counted again here as a subset of qoida.realtime.streams.refused")
                .register(meters);
        this.coalescedSignals = Counter.builder("qoida.realtime.signals.coalesced")
                .description("Signals folded into an already-pending frame by a cadence cap")
                .register(meters);
    }

    /**
     * Registers a connection, or refuses it.
     *
     * @param lastEventId  what the client last saw. Any value at all produces a
     *                     {@code resync} frame rather than a replay: there is no
     *                     buffer, because a buffer is server-side per-client state
     *                     and a client that missed three seconds of a five-second
     *                     map is better served by re-reading its scope than by
     *                     receiving three stale frames in order.
     * @param tokenExpiry  when the access token dies, and with it this stream
     */
    public Connection open(UUID tenantId, String principalSubject, Set<Subscription> subscriptions,
            StreamSink sink, Instant tokenExpiry, String lastEventId) {

        if (connections.size() >= MAXIMUM_STREAMS) {
            refusedConnects.increment();
            throw new StreamCapReachedException(
                    ("This replica already holds %d streams (ADR 0045). Poll instead; every live "
                            + "surface has a polling path that works.").formatted(MAXIMUM_STREAMS));
        }

        long held = streamsHeldBy(tenantId);
        if (held >= TENANT_GUARANTEED_STREAMS
                && connections.size() >= MAXIMUM_STREAMS - RESERVED_FOR_OTHER_TENANTS) {
            refusedConnects.increment();
            refusedTenantShare.increment();
            // At warn and naming the tenant: this one is actionable in a way the
            // replica simply being full is not, because the tenant that is being
            // held back and the tenant that filled the box are different people.
            log.warn("Refusing a stream for tenant {}: it holds {} of this replica's {} and the "
                            + "last {} are reserved for other tenants (ADR 0045)",
                    tenantId, held, MAXIMUM_STREAMS, RESERVED_FOR_OTHER_TENANTS);
            throw new StreamCapReachedException(
                    ("This tenant already holds %d of this replica's %d streams and the last %d are "
                            + "kept for other tenants (ADR 0045). Poll instead; every live surface "
                            + "has a polling path that works.")
                            .formatted(held, MAXIMUM_STREAMS, RESERVED_FOR_OTHER_TENANTS));
        }

        Connection connection = new Connection(UUID.randomUUID().toString(), tenantId,
                principalSubject, Set.copyOf(subscriptions), sink, tokenExpiry, clock.instant());
        connections.put(connection.id(), connection);

        if (connections.size() >= WARNING_THRESHOLD) {
            log.warn("{} open streams on this replica, past the {} dashboard threshold and "
                    + "approaching the {} cap (ADR 0045)",
                    connections.size(), WARNING_THRESHOLD, MAXIMUM_STREAMS);
        }

        if (lastEventId != null && !lastEventId.isBlank()) {
            write(connection, "resync", UUID.randomUUID().toString(), Map.of(
                    "reason", "NO_REPLAY_BUFFER",
                    "channels", subscriptions.stream().map(s -> s.channel().code()).sorted().toList()));
        }
        return connection;
    }

    public void close(String connectionId) {
        Connection connection = connections.remove(connectionId);
        if (connection != null) {
            connection.sink().complete();
        }
    }

    /**
     * Routes a signal to every connection subscribed to its channel and scope.
     *
     * <p>Nothing is written here. The signal replaces whatever was pending for
     * that subscription and the cadence cap decides when it leaves, which is what
     * makes forty bulk assignments one frame instead of forty.
     */
    public void onSignal(RealtimeSignal signal) {
        Subscription subscription = signal.subscription();
        for (Connection connection : connections.values()) {
            if (!connection.tenantId().equals(signal.tenantId())
                    || !connection.subscriptions().contains(subscription)) {
                continue;
            }
            synchronized (connection.pending()) {
                Pending replaced = connection.pending().put(subscription,
                        new Pending(signal, connection.pendingSince(subscription, clock.instant())));
                if (replaced != null) {
                    coalescedSignals.increment();
                }
            }
        }
    }

    /**
     * One tick. Flushes what has waited out its cadence cap, sends heartbeats,
     * and closes what has expired.
     *
     * <p>Called on a schedule in production and directly from tests, which is the
     * whole reason it takes {@code now} rather than reading the clock: a test for
     * "forty assignments produce one frame per 250 ms window" that has to sleep is
     * a test that is slow and flaky in the same change.
     */
    public void tick(Instant now) {
        for (Connection connection : List.copyOf(connections.values())) {
            if (!now.isBefore(connection.tokenExpiry())) {
                log.debug("Closing stream {}: its access token expired", connection.id());
                closeWith(connection, "TOKEN_EXPIRED");
                continue;
            }
            try {
                flush(connection, now);
                if (Duration.between(connection.lastWriteAt(), now).compareTo(HEARTBEAT_INTERVAL) >= 0) {
                    connection.sink().heartbeat();
                    connection.touch(now);
                }
            } catch (RuntimeException failure) {
                // A client that went away mid-write is the ordinary case, not an
                // incident: a browser tab closed, a wall display rebooted, a
                // laptop lid shut. It is removed and it reconnects.
                log.debug("Stream {} failed and was dropped", connection.id(), failure);
                connections.remove(connection.id());
                connection.sink().completeWithError(failure);
            }
        }
    }

    /**
     * ADR 0033's {@code TenantGrantsChanged}, applied to open streams.
     *
     * <p>Closing rather than re-evaluating each subscription: a stream is a set
     * of channels fixed for its life, the client already knows how to reconnect
     * and resync, and a partially narrowed stream is a state nobody would test.
     */
    public int closeForPrincipal(String principalSubject, String reason) {
        List<Connection> affected = connections.values().stream()
                .filter(connection -> connection.principalSubject().equals(principalSubject))
                .toList();
        affected.forEach(connection -> closeWith(connection, reason));
        return affected.size();
    }

    public int openStreams() {
        return connections.size();
    }

    /**
     * How many of this replica's streams one tenant holds.
     *
     * <p>Counted from the connection map rather than kept in a parallel counter.
     * A counter has to be decremented on four exit paths — close, token expiry,
     * grants change, and a client that went away mid-write — and the one that gets
     * missed reads as a tenant permanently at its share with no stream to show for
     * it. Walking at most {@link #MAXIMUM_STREAMS} entries on connect is cheaper
     * than that class of bug.
     */
    public long streamsHeldBy(UUID tenantId) {
        return connections.values().stream()
                .filter(connection -> connection.tenantId().equals(tenantId))
                .count();
    }

    public long framesSent() {
        return framesSent.get();
    }

    private void closeWith(Connection connection, String reason) {
        connections.remove(connection.id());
        try {
            write(connection, "closing", UUID.randomUUID().toString(),
                    Map.of("reason", reason,
                            // Jittered so a deploy's herd does not arrive together.
                            // ADR 0034 has no rolling deploy, so every stream on the
                            // box dies at once and every client resyncs at once.
                            "reconnectAfterSecondsMin", 1, "reconnectAfterSecondsMax", 10));
        } catch (RuntimeException ignored) {
            // The socket is already gone, which is the common case here.
        }
        connection.sink().complete();
    }

    private void flush(Connection connection, Instant now) {
        Map<Subscription, Pending> due = new LinkedHashMap<>();
        synchronized (connection.pending()) {
            connection.pending().entrySet().removeIf(entry -> {
                Duration waited = Duration.between(entry.getValue().firstQueuedAt(), now);
                if (waited.compareTo(entry.getKey().channel().cadenceCap()) < 0) {
                    return false;
                }
                due.put(entry.getKey(), entry.getValue());
                return true;
            });
        }

        for (Map.Entry<Subscription, Pending> entry : due.entrySet()) {
            emit(connection, entry.getKey(), entry.getValue().signal(), now);
        }
    }

    private void emit(Connection connection, Subscription subscription,
            RealtimeSignal signal, Instant now) {

        StreamChannel channel = subscription.channel();
        if (channel.frameClass() == StreamChannel.FrameClass.SNAPSHOT) {
            SnapshotSource source = snapshotSources.get(channel);
            if (source == null) {
                // Honest degradation rather than a silent gap: the client is told
                // to re-read, which is the polling path it must have anyway.
                log.debug("No snapshot source for {}; sending a signal instead", channel);
                writeSignal(connection, signal, now);
                return;
            }
            Optional<Object> payload = source.snapshot(connection.tenantId(), subscription.scopeKey());
            if (payload.isEmpty()) {
                return;
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("channel", channel.code());
            frame.put("scope", subscription.scopeKey().canonical());
            frame.put("occurredAt", signal.occurredAt().toString());
            frame.put("snapshot", payload.get());
            write(connection, "snapshot", signal.signalId().toString(), frame);
            connection.touch(now);
            return;
        }
        writeSignal(connection, signal, now);
        connection.touch(now);
    }

    private void writeSignal(Connection connection, RealtimeSignal signal, Instant now) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("channel", signal.channel().code());
        frame.put("scope", signal.scopeKey().canonical());
        frame.put("resourceType", signal.resourceType());
        frame.put("resourceId", signal.resourceId() == null ? null : signal.resourceId().toString());
        frame.put("version", signal.version());
        frame.put("occurredAt", signal.occurredAt().toString());
        write(connection, "signal", signal.signalId().toString(), frame);
        connection.touch(now);
    }

    private void write(Connection connection, String eventName, String id, Object payload) {
        connection.sink().send(eventName, id, json.writeValueAsString(payload));
        framesSent.incrementAndGet();
    }

    /** One open stream. Immutable except for its pending map and last write time. */
    public static final class Connection {

        private final String id;
        private final UUID tenantId;
        private final String principalSubject;
        private final Set<Subscription> subscriptions;
        private final StreamSink sink;
        private final Instant tokenExpiry;
        private final Map<Subscription, Pending> pending = new LinkedHashMap<>();
        private volatile Instant lastWriteAt;

        Connection(String id, UUID tenantId, String principalSubject, Set<Subscription> subscriptions,
                StreamSink sink, Instant tokenExpiry, Instant openedAt) {
            this.id = id;
            this.tenantId = tenantId;
            this.principalSubject = principalSubject;
            this.subscriptions = subscriptions;
            this.sink = sink;
            this.tokenExpiry = tokenExpiry;
            this.lastWriteAt = openedAt;
        }

        public String id() {
            return id;
        }

        UUID tenantId() {
            return tenantId;
        }

        String principalSubject() {
            return principalSubject;
        }

        Set<Subscription> subscriptions() {
            return subscriptions;
        }

        StreamSink sink() {
            return sink;
        }

        Instant tokenExpiry() {
            return tokenExpiry;
        }

        Map<Subscription, Pending> pending() {
            return pending;
        }

        Instant lastWriteAt() {
            return lastWriteAt;
        }

        void touch(Instant now) {
            this.lastWriteAt = now;
        }

        /**
         * When the current coalescing window opened. A replacing signal keeps the
         * original instant, so a stream of changes is emitted once per cap rather
         * than being deferred indefinitely by its own busyness.
         */
        Instant pendingSince(Subscription subscription, Instant now) {
            Pending existing = pending.get(subscription);
            return existing == null ? now : existing.firstQueuedAt();
        }
    }

    private record Pending(RealtimeSignal signal, Instant firstQueuedAt) {
    }

    /** Thrown when this replica is already holding as many streams as it will. */
    public static final class StreamCapReachedException extends RuntimeException {
        public StreamCapReachedException(String message) {
            super(message);
        }
    }
}
