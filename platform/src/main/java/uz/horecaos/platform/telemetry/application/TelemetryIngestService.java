package uz.horecaos.platform.telemetry.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.DutySessionStatus;
import uz.horecaos.platform.telemetry.domain.Geohash;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.domain.TrackObservation;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.LivePositionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.TrackWindowRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * What happens to a batch of observations from a courier's handset (ADR 0045).
 *
 * <p>The order of the checks below is the design, not an implementation detail.
 *
 * <ol>
 * <li><strong>Is there an open duty session?</strong> An observation arriving
 *     with none is rejected with a {@code 422} and is not stored. Collection that
 *     continues after a courier signs off is the failure this whole feature is
 *     built around, and it has to fail loudly rather than accumulate quietly.
 * <li><strong>Is the session suspended?</strong> A break stops collection. A
 *     courier on break is not assignable, so the pin had no operational use.
 * <li><strong>Does the gate allow it?</strong> {@code ON_ASSIGNMENT} drops a
 *     batch from a courier carrying nothing.
 * <li><strong>Is the device inside its cadence?</strong> One batch per five
 *     seconds per courier, because the platform owns the lever over battery,
 *     data cost, and write volume, and a device that chose its own would take it
 *     away.
 * </ol>
 *
 * <p>Only then is anything written, and what is written is two different things
 * with two different lifetimes. The freshest drawable observation becomes the
 * live pin, which is deleted an hour after the session closes. Every observation
 * — including the ones too coarse to draw — goes into a one-minute encrypted
 * track window, which is dropped with its partition after the retention floor.
 *
 * <p>No coordinate, accuracy, speed, heading, or battery value leaves this class
 * except into those two tables. The signal it publishes afterwards carries a
 * courier id, a time, and a scope key.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    /** ADR 0031 conventions put the limit key in one place per operation. */
    private static final String RATE_LIMIT_OPERATION = "telemetry.observations.ingest";

    private final JdbcTelemetryStore store;
    private final DutySessionService sessions;
    private final FieldProtection protection;
    private final ConfigurationResolver configuration;
    private final RealtimeSignalPublisher signals;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final Clock clock;

    public TelemetryIngestService(JdbcTelemetryStore store, DutySessionService sessions,
            FieldProtection protection, ConfigurationResolver configuration,
            RealtimeSignalPublisher signals, RateLimiter rateLimiter,
            ObjectMapper json, Clock clock) {
        this.store = store;
        this.sessions = sessions;
        this.protection = protection;
        this.configuration = configuration;
        this.signals = signals;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public IngestOutcome ingest(UUID tenantId, UUID courierId, int activeAssignmentCount,
            List<TrackObservation> batch) {

        if (batch.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A batch carries at least one observation");
        }
        if (batch.size() > LivePositionRules.MAXIMUM_BATCH_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A batch carries at most %d observations".formatted(LivePositionRules.MAXIMUM_BATCH_SIZE));
        }

        DutySessionRow session = sessions.openSession(tenantId, courierId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                        "This courier has no open duty session, so nothing about their location is "
                                + "collected. Sign on first (ADR 0045).",
                        Map.of("reason", "NO_OPEN_DUTY_SESSION")));

        if (session.status() == DutySessionStatus.SUSPENDED) {
            // Not an error the app should retry into. A break is a state the
            // courier chose, and the honest answer is that nothing was stored.
            return IngestOutcome.onBreak();
        }
        if (!session.status().accepts()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "This duty session is closed", Map.of("reason", "DUTY_SESSION_CLOSED"));
        }

        CollectionGate gate = resolveGate(tenantId, session);
        if (!gate.collects(activeAssignmentCount)) {
            return IngestOutcome.blockedByGate(gate);
        }

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(RATE_LIMIT_OPERATION, tenantId.toString(), courierId.toString()),
                // Fails closed. A device posting faster than the cadence is either
                // misconfigured or duplicating, and both cost the courier battery
                // and data they pay for themselves.
                new RateLimiter.Policy(1, LivePositionRules.MINIMUM_BATCH_INTERVAL, false));
        if (!decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "One batch per %d seconds per courier"
                            .formatted(LivePositionRules.MINIMUM_BATCH_INTERVAL.toSeconds()),
                    Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }

        Instant now = clock.instant();
        List<TrackObservation> ordered = batch.stream()
                .sorted(Comparator.comparing(TrackObservation::capturedAt))
                .toList();

        int windowsWritten = writeTrackWindows(session, ordered, now);
        boolean pinMoved = writeLivePosition(session, ordered, activeAssignmentCount, now);

        if (pinMoved) {
            // The record carries a courier id, a time, and a scope key. A replica
            // receiving it reads the live row it already has access to and pushes
            // a snapshot to the subscribers authorized for that location; the
            // coordinate never touches a topic.
            signals.publish(RealtimeSignal.of(tenantId, StreamChannel.COURIER_POSITIONS,
                    ScopeKey.location(session.locationId()), "COURIER", courierId, null, now));
        }

        return new IngestOutcome(ordered.size(), windowsWritten, pinMoved, gate, false);
    }

    /**
     * Groups the batch into one-minute windows and stores each as a single
     * protected value.
     *
     * <p>A minute is the grain because it is the unit a dispute is argued in —
     * "where was he at ten past" — and because one row per observation would be
     * six rows a minute per courier, of the order of 360,000 a day per tenant, on
     * a box that also runs Kafka, Keycloak, MinIO, and OpenBao.
     */
    private int writeTrackWindows(DutySessionRow session, List<TrackObservation> ordered, Instant now) {
        Map<Instant, List<TrackObservation>> byMinute = new TreeMap<>();
        for (TrackObservation observation : ordered) {
            byMinute.computeIfAbsent(observation.capturedAt().truncatedTo(ChronoUnit.MINUTES),
                    minute -> new ArrayList<>()).add(observation);
        }

        int written = 0;
        for (Map.Entry<Instant, List<TrackObservation>> entry : byMinute.entrySet()) {
            List<TrackObservation> window = entry.getValue();
            TrackObservation first = window.getFirst();
            TrackObservation last = window.getLast();

            UUID windowId = UUID.randomUUID();
            String plaintext = json.writeValueAsString(window.stream()
                    .map(TelemetryIngestService::wireForm)
                    .toList());

            String protectedTrack = protection.protect(
                    session.tenantId(), DataClass.PERSONAL_SENSITIVE,
                    new FieldProtection.RecordRef(
                            "fulfillment.courier_location_tracks", "protected_track", windowId),
                    plaintext)
                    .serialize();

            written += store.upsertTrackWindow(new TrackWindowRow(
                    windowId, session.tenantId(), session.courierId(), session.id(),
                    entry.getKey(), last.capturedAt(),
                    Geohash.encode5(first.latitude(), first.longitude()),
                    Geohash.encode5(last.latitude(), last.longitude()),
                    window.size(), observedDistanceMeters(window), protectedTrack, now)) ? 1 : 0;
        }
        return written;
    }

    /**
     * The pin: the newest observation that is both fresh enough and precise
     * enough to be drawn.
     *
     * <p>A coarse fix is not an error and is not discarded — it is already in the
     * track above. It simply does not become a dot, because a 900 m accuracy
     * circle rendered as a dot sends a courier to the wrong street.
     */
    private boolean writeLivePosition(DutySessionRow session, List<TrackObservation> ordered,
            int activeAssignmentCount, Instant now) {

        for (int index = ordered.size() - 1; index >= 0; index--) {
            TrackObservation candidate = ordered.get(index);
            if (!LivePositionRules.freshEnoughForTheMap(candidate.capturedAt(), now)
                    || !LivePositionRules.drawable(candidate.accuracyMeters())) {
                continue;
            }
            return store.upsertLivePosition(new LivePositionRow(
                    session.tenantId(), session.courierId(), session.id(),
                    session.brandId(), session.locationId(),
                    candidate.latitude(), candidate.longitude(), candidate.accuracyMeters(),
                    candidate.headingDegrees(), candidate.speedMps(),
                    candidate.batteryPercent(), candidate.deviceCharging(),
                    activeAssignmentCount, candidate.capturedAt(), now));
        }
        return false;
    }

    /**
     * Observed distance for the window, in whole metres.
     *
     * <p>Named here rather than hidden inside a query so that the sentence
     * attached to it is unavoidable: <strong>nothing pays a courier from this
     * number.</strong> ADR 0042 accrues on the routing distance quoted at
     * assignment and snapshotted onto the earning row. A telemetry distance moves
     * with detours, with drift in Tashkent's courtyards, and with a handset that
     * lost signal for a block, and neither party can see it before the trip.
     */
    private static int observedDistanceMeters(List<TrackObservation> window) {
        int total = 0;
        for (int index = 1; index < window.size(); index++) {
            TrackObservation previous = window.get(index - 1);
            TrackObservation current = window.get(index);
            total += Geohash.distanceMeters(previous.latitude(), previous.longitude(),
                    current.latitude(), current.longitude());
        }
        return total;
    }

    private CollectionGate resolveGate(UUID tenantId, DutySessionRow session) {
        String configured = configuration.value(
                TelemetryConfigurationKeys.COLLECTION_GATE,
                ResourceScope.location(tenantId, session.brandId(), session.locationId()));

        return CollectionGate.find(configured).orElseGet(() -> {
            // A gate nobody can parse must not silently become the wider one.
            log.warn("Unparseable collection gate \"{}\"; falling back to the session's own {}",
                    configured, session.collectionGate());
            return session.collectionGate();
        });
    }

    /**
     * The serialized shape inside the protected value.
     *
     * <p>Short keys, because this is written six times a minute per courier and
     * the ciphertext is the row. Battery and charging state are deliberately
     * absent: they are live-row facts, and a battery history is a work-pattern
     * archive with no operational use.
     */
    private static Map<String, Object> wireForm(TrackObservation observation) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("t", observation.capturedAt().toString());
        shape.put("lat", observation.latitude());
        shape.put("lon", observation.longitude());
        shape.put("acc", observation.accuracyMeters());
        if (observation.headingDegrees() != null) {
            shape.put("hdg", observation.headingDegrees());
        }
        if (observation.speedMps() != null) {
            shape.put("spd", observation.speedMps());
        }
        return shape;
    }

    /**
     * @param suspended true when a break was running, which is not a failure and
     *                  is reported to the app so its on-duty indicator can say
     *                  truthfully that collection has stopped
     */
    public record IngestOutcome(
            int observationsAccepted, int windowsWritten, boolean livePositionMoved,
            CollectionGate gate, boolean suspended) {

        static IngestOutcome onBreak() {
            return new IngestOutcome(0, 0, false, CollectionGate.ON_DUTY, true);
        }

        static IngestOutcome blockedByGate(CollectionGate gate) {
            return new IngestOutcome(0, 0, false, gate, false);
        }
    }
}
