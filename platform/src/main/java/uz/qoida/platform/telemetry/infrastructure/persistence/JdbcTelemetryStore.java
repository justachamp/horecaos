package uz.qoida.platform.telemetry.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.telemetry.domain.CollectionGate;
import uz.qoida.platform.telemetry.domain.DutySessionStatus;

/**
 * Telemetry persistence (ADR 0045).
 *
 * <p>Three rules run through every statement here, and each answers a specific
 * way this table set can go wrong.
 *
 * <p><strong>The tenant predicate is always inside the query.</strong> A courier
 * id arrives from a handset; matching on it alone would put one tenant's fleet on
 * another tenant's map, and the map is the one surface in this module a person
 * looks at rather than a machine.
 *
 * <p><strong>The live position only ever moves forward.</strong> The upsert
 * carries its own {@code WHERE excluded.captured_at > …} predicate, so a
 * reconnecting device replaying ten minutes of buffer cannot walk the pin
 * backwards. That is a property of the statement rather than of the caller,
 * because the caller is a batch loop and a loop that has to remember an ordering
 * rule is a loop that eventually forgets it.
 *
 * <p><strong>Nothing here decrypts.</strong> The track's coordinates cross this
 * class as an already-protected string; only {@code CourierTrackRevealService}
 * turns one back into a path, and only after writing the ADR 0027 audit entry
 * that says who did and why.
 */
@Repository
public class JdbcTelemetryStore {

    private final JdbcClient jdbc;

    public JdbcTelemetryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------- duty sessions

    private static final String SELECT_SESSION = """
            SELECT id, tenant_id, brand_id, location_id, courier_id, shift_id, device_id,
                   status, collection_gate, registration_checked_at, registration_valid_until,
                   opened_by_subject, started_at, suspended_at, ended_at, end_reason, version
              FROM fulfillment.courier_duty_sessions
            """;

    public void insertDutySession(DutySessionRow session) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_duty_sessions (
                    id, tenant_id, brand_id, location_id, courier_id, shift_id, device_id,
                    status, collection_gate, registration_checked_at, registration_valid_until,
                    opened_by_subject, started_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :shiftId, :deviceId,
                    :status, :gate, :checkedAt, :validUntil, :openedBy, :startedAt, 1, :now, :now)
                """)
                .param("id", session.id())
                .param("tenantId", session.tenantId())
                .param("brandId", session.brandId())
                .param("locationId", session.locationId())
                .param("courierId", session.courierId())
                .param("shiftId", session.shiftId())
                .param("deviceId", session.deviceId())
                .param("status", session.status().name())
                .param("gate", session.collectionGate().name())
                .param("checkedAt", utc(session.registrationCheckedAt()))
                .param("validUntil", session.registrationValidUntil())
                .param("openedBy", session.openedBySubject())
                .param("startedAt", utc(session.startedAt()))
                .param("now", utc(session.startedAt()))
                .update();
    }

    public Optional<DutySessionRow> findOpenSession(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND ended_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcTelemetryStore::readSession)
                .optional();
    }

    public Optional<DutySessionRow> findSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .query(JdbcTelemetryStore::readSession)
                .optional();
    }

    public List<DutySessionRow> openSessionsAtLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId AND ended_at IS NULL
                 ORDER BY started_at
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcTelemetryStore::readSession)
                .list();
    }

    /**
     * Moves a session between OPEN and SUSPENDED, naming the status it expects.
     *
     * <p>The row count decides who won. A break started twice, or ended by the
     * courier and the sweeper in the same second, both reduce to the same
     * question, and PostgreSQL answers it rather than whichever request arrived
     * first.
     */
    public boolean transitionSession(UUID tenantId, UUID sessionId,
            DutySessionStatus expected, DutySessionStatus next, Instant at) {

        return jdbc.sql("""
                UPDATE fulfillment.courier_duty_sessions
                   SET status = :next,
                       suspended_at = CASE WHEN :next = 'SUSPENDED' THEN :at ELSE NULL END,
                       version = version + 1,
                       updated_at = :at
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = :expected AND ended_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .param("expected", expected.name())
                .param("next", next.name())
                .param("at", utc(at))
                .update() == 1;
    }

    public boolean closeSession(UUID tenantId, UUID sessionId, String endReason, Instant at) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_duty_sessions
                   SET status = 'CLOSED', suspended_at = NULL,
                       ended_at = :at, end_reason = :reason,
                       version = version + 1, updated_at = :at
                 WHERE tenant_id = :tenantId AND id = :id AND ended_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .param("reason", endReason)
                .param("at", utc(at))
                .update() == 1;
    }

    // ------------------------------------------------------------- live positions

    /**
     * Writes the pin, and refuses to move it backwards.
     *
     * <p>The conflict clause is the staleness rule expressed where it cannot be
     * skipped. An observation older than the stored one updates nothing and
     * reports zero rows, which the caller counts rather than treats as an error:
     * a buffered batch is <em>expected</em> to contain readings the live row has
     * already passed.
     */
    public boolean upsertLivePosition(LivePositionRow position) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", position.tenantId());
        params.put("courierId", position.courierId());
        params.put("sessionId", position.dutySessionId());
        params.put("brandId", position.brandId());
        params.put("locationId", position.locationId());
        params.put("latitude", position.latitude());
        params.put("longitude", position.longitude());
        params.put("accuracy", position.accuracyMeters());
        params.put("heading", position.headingDegrees());
        params.put("speed", position.speedMps());
        params.put("battery", position.batteryPercent());
        params.put("charging", position.deviceCharging());
        params.put("assignments", position.activeAssignmentCount());
        params.put("capturedAt", utc(position.capturedAt()));
        params.put("receivedAt", utc(position.receivedAt()));

        return jdbc.sql("""
                INSERT INTO fulfillment.courier_positions_live (
                    tenant_id, courier_id, duty_session_id, brand_id, location_id,
                    position, accuracy_meters, heading_degrees, speed_mps,
                    battery_percent, device_charging, active_assignment_count,
                    captured_at, received_at)
                VALUES (:tenantId, :courierId, :sessionId, :brandId, :locationId,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :accuracy, :heading, :speed, :battery, :charging, :assignments,
                    :capturedAt, :receivedAt)
                ON CONFLICT (tenant_id, courier_id) DO UPDATE
                   SET duty_session_id = excluded.duty_session_id,
                       brand_id = excluded.brand_id,
                       location_id = excluded.location_id,
                       position = excluded.position,
                       accuracy_meters = excluded.accuracy_meters,
                       heading_degrees = excluded.heading_degrees,
                       speed_mps = excluded.speed_mps,
                       battery_percent = excluded.battery_percent,
                       device_charging = excluded.device_charging,
                       active_assignment_count = excluded.active_assignment_count,
                       captured_at = excluded.captured_at,
                       received_at = excluded.received_at
                 WHERE excluded.captured_at > fulfillment.courier_positions_live.captured_at
                """)
                .params(params)
                .update() == 1;
    }

    public List<LivePositionRow> livePositionsAtLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT tenant_id, courier_id, duty_session_id, brand_id, location_id,
                       ST_Y(position::geometry) AS latitude, ST_X(position::geometry) AS longitude,
                       accuracy_meters, heading_degrees, speed_mps, battery_percent,
                       device_charging, active_assignment_count, captured_at, received_at
                  FROM fulfillment.courier_positions_live
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY courier_id
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcTelemetryStore::readLivePosition)
                .list();
    }

    public Optional<LivePositionRow> livePosition(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT tenant_id, courier_id, duty_session_id, brand_id, location_id,
                       ST_Y(position::geometry) AS latitude, ST_X(position::geometry) AS longitude,
                       accuracy_meters, heading_degrees, speed_mps, battery_percent,
                       device_charging, active_assignment_count, captured_at, received_at
                  FROM fulfillment.courier_positions_live
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcTelemetryStore::readLivePosition)
                .optional();
    }

    /**
     * Straight-line metres from a branch to each named courier's live pin, with
     * the accuracy and capture instant the caller needs in order to decide
     * whether to believe it (ADR 0045).
     *
     * <p>The branch coordinate is a one-column read into {@code tenant.locations}
     * — a SQL join and not a module dependency, the same shape
     * {@code JdbcFiscalLifecycleStore} and {@code JdbcDineInStore} already use.
     * A restaurant's address is published by the merchant, printed on the
     * receipt and handed to every courier, which is why V0023 left it in clear;
     * this is the read that pays for that decision.
     *
     * <p>A branch with no pin — {@code coordinate_source = 'NOT_GEOCODED'} — is
     * simply absent from the result. Measuring from a null island puts every
     * courier six thousand kilometres away in the same direction, which ranks
     * them in an order that looks deliberate and is arbitrary.
     *
     * <p>{@code ST_Distance} on {@code geography} is metres on the spheroid, so
     * no unit conversion happens in Java. The freshness and accuracy rules are
     * deliberately <em>not</em> in this statement: they are
     * {@link uz.qoida.platform.telemetry.domain.LivePositionRules}, they already
     * decide what the map may draw, and a rule that lives in a WHERE clause is
     * the kind that is quietly wrong for months.
     */
    public List<ProximityRow> metresFromBranch(UUID tenantId, UUID locationId,
            Collection<UUID> courierIds) {

        if (courierIds.isEmpty()) {
            // An empty collection renders as `IN ()`, which PostgreSQL rejects.
            return List.of();
        }
        return jdbc.sql("""
                SELECT live.courier_id,
                       ST_Distance(
                           live.position,
                           ST_SetSRID(ST_MakePoint(branch.longitude, branch.latitude), 4326)
                               ::geography) AS metres,
                       live.accuracy_meters,
                       live.captured_at
                  FROM fulfillment.courier_positions_live live
                  JOIN tenant.locations branch
                    ON branch.tenant_id = live.tenant_id AND branch.id = :locationId
                 WHERE live.tenant_id = :tenantId
                   AND live.courier_id IN (:courierIds)
                   AND branch.latitude IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("courierIds", courierIds)
                .query((row, rowNumber) -> new ProximityRow(
                        row.getObject("courier_id", UUID.class),
                        row.getDouble("metres"),
                        row.getBigDecimal("accuracy_meters").doubleValue(),
                        row.getObject("captured_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * The first retention tier, and the one that pays for storing coordinates
     * unencrypted: a live row is deleted an hour after its duty session closed.
     *
     * @return how many pins were removed, which the sweeper logs so a retention
     *         rule that stopped running is visible as a run that deletes nothing
     */
    public int deleteLivePositionsForSessionsClosedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM fulfillment.courier_positions_live live
                 USING fulfillment.courier_duty_sessions session
                 WHERE session.id = live.duty_session_id
                   AND session.tenant_id = live.tenant_id
                   AND session.ended_at IS NOT NULL
                   AND session.ended_at < :cutoff
                """)
                .param("cutoff", utc(cutoff))
                .update();
    }

    // --------------------------------------------------------------------- tracks

    /**
     * Writes one minute of a courier's movement as a single protected value.
     *
     * <p>Idempotent on the window, which is ADR 0045's natural key at the grain
     * this table stores. A replayed batch writes the same minute with the same
     * count and changes nothing; a batch that completes a minute the previous one
     * only started carries more observations and replaces it. Neither case needs
     * an ADR 0031 idempotency record, which is the narrow exemption ADR 0045
     * names and the reason six rows a minute per courier do not land in that
     * table.
     */
    public boolean upsertTrackWindow(TrackWindowRow window) {
        return jdbc.sql("""
                INSERT INTO fulfillment.courier_location_tracks (
                    id, tenant_id, courier_id, duty_session_id, window_start, window_end,
                    geohash5_first, geohash5_last, observation_count, distance_meters,
                    protected_track, created_at)
                VALUES (:id, :tenantId, :courierId, :sessionId, :windowStart, :windowEnd,
                    :first, :last, :count, :distance, :protectedTrack, :now)
                ON CONFLICT (window_start, tenant_id, courier_id) DO UPDATE
                   SET window_end = excluded.window_end,
                       geohash5_first = excluded.geohash5_first,
                       geohash5_last = excluded.geohash5_last,
                       observation_count = excluded.observation_count,
                       distance_meters = excluded.distance_meters,
                       protected_track = excluded.protected_track
                 WHERE excluded.observation_count
                       > fulfillment.courier_location_tracks.observation_count
                """)
                .param("id", window.id())
                .param("tenantId", window.tenantId())
                .param("courierId", window.courierId())
                .param("sessionId", window.dutySessionId())
                .param("windowStart", utc(window.windowStart()))
                .param("windowEnd", utc(window.windowEnd()))
                .param("first", window.geohash5First())
                .param("last", window.geohash5Last())
                .param("count", window.observationCount())
                .param("distance", window.distanceMeters())
                .param("protectedTrack", window.protectedTrack())
                .param("now", utc(window.createdAt()))
                .update() == 1;
    }

    /** The reveal's read. Always bounded by a window, never "everything for this courier". */
    public List<TrackWindowRow> trackWindows(UUID tenantId, UUID courierId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, tenant_id, courier_id, duty_session_id, window_start, window_end,
                       geohash5_first, geohash5_last, observation_count, distance_meters,
                       protected_track, created_at
                  FROM fulfillment.courier_location_tracks
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                   AND window_start >= :from AND window_start < :to
                 ORDER BY window_start
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcTelemetryStore::readTrackWindow)
                .list();
    }

    /** What a summary is computed from, without decrypting anything. */
    public Optional<TrackAggregate> aggregateForSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT coalesce(sum(distance_meters), 0)::int AS distance_meters,
                       coalesce(sum(observation_count), 0)::int AS observation_count,
                       min(window_start) AS first_observed_at,
                       max(window_end) AS last_observed_at
                  FROM fulfillment.courier_location_tracks
                 WHERE tenant_id = :tenantId AND duty_session_id = :sessionId
                """)
                .param("tenantId", tenantId)
                .param("sessionId", sessionId)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime first = resultSet.getObject("first_observed_at", OffsetDateTime.class);
                    if (first == null) {
                        return null;
                    }
                    return new TrackAggregate(
                            resultSet.getInt("distance_meters"),
                            resultSet.getInt("observation_count"),
                            first.toInstant(),
                            resultSet.getObject("last_observed_at", OffsetDateTime.class).toInstant());
                })
                .optional()
                .filter(aggregate -> aggregate != null);
    }

    // ------------------------------------------------------------------ summaries

    public void insertSummary(TrackSummaryRow summary) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", summary.id());
        params.put("tenantId", summary.tenantId());
        params.put("courierId", summary.courierId());
        params.put("sessionId", summary.dutySessionId());
        params.put("shipmentId", summary.shipmentId());
        params.put("distance", summary.distanceMeters());
        params.put("count", summary.observationCount());
        params.put("firstAt", utc(summary.firstObservedAt()));
        params.put("lastAt", utc(summary.lastObservedAt()));
        params.put("pickup", summary.protectedPickupPoint());
        params.put("delivery", summary.protectedDeliveryPoint());
        params.put("now", utc(summary.createdAt()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_track_summaries (
                    id, tenant_id, courier_id, duty_session_id, shipment_id,
                    distance_meters, observation_count, first_observed_at, last_observed_at,
                    protected_pickup_point, protected_delivery_point, created_at)
                VALUES (:id, :tenantId, :courierId, :sessionId, :shipmentId,
                    :distance, :count, :firstAt, :lastAt, :pickup, :delivery, :now)
                """)
                .params(params)
                .update();
    }

    public List<TrackSummaryRow> summariesForCourier(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT id, tenant_id, courier_id, duty_session_id, shipment_id,
                       distance_meters, observation_count, first_observed_at, last_observed_at,
                       protected_pickup_point, protected_delivery_point, created_at
                  FROM fulfillment.courier_track_summaries
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                 ORDER BY first_observed_at
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcTelemetryStore::readSummary)
                .list();
    }

    // ----------------------------------------------------------------- row mapping

    private static DutySessionRow readSession(ResultSet row, int rowNumber) throws SQLException {
        return new DutySessionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("courier_id", UUID.class),
                row.getObject("shift_id", UUID.class),
                row.getString("device_id"),
                DutySessionStatus.valueOf(row.getString("status")),
                CollectionGate.valueOf(row.getString("collection_gate")),
                row.getObject("registration_checked_at", OffsetDateTime.class).toInstant(),
                row.getObject("registration_valid_until", LocalDate.class),
                row.getString("opened_by_subject"),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                instantOrNull(row, "suspended_at"),
                instantOrNull(row, "ended_at"),
                row.getString("end_reason"),
                row.getInt("version"));
    }

    private static LivePositionRow readLivePosition(ResultSet row, int rowNumber) throws SQLException {
        return new LivePositionRow(
                row.getObject("tenant_id", UUID.class),
                row.getObject("courier_id", UUID.class),
                row.getObject("duty_session_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getDouble("latitude"),
                row.getDouble("longitude"),
                row.getBigDecimal("accuracy_meters").doubleValue(),
                // getDouble answers 0 for SQL NULL, and 0 is a legitimate heading
                // and a legitimate speed. Read through getBigDecimal, which
                // answers null for null — and which is also the only accessor the
                // driver offers for a numeric column, since getObject(Double.class)
                // refuses the conversion outright.
                doubleOrNull(row, "heading_degrees"),
                doubleOrNull(row, "speed_mps"),
                row.getObject("battery_percent", Integer.class),
                row.getObject("device_charging", Boolean.class),
                row.getInt("active_assignment_count"),
                row.getObject("captured_at", OffsetDateTime.class).toInstant(),
                row.getObject("received_at", OffsetDateTime.class).toInstant());
    }

    private static TrackWindowRow readTrackWindow(ResultSet row, int rowNumber) throws SQLException {
        return new TrackWindowRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("courier_id", UUID.class),
                row.getObject("duty_session_id", UUID.class),
                row.getObject("window_start", OffsetDateTime.class).toInstant(),
                row.getObject("window_end", OffsetDateTime.class).toInstant(),
                row.getString("geohash5_first"),
                row.getString("geohash5_last"),
                row.getInt("observation_count"),
                row.getInt("distance_meters"),
                row.getString("protected_track"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static TrackSummaryRow readSummary(ResultSet row, int rowNumber) throws SQLException {
        return new TrackSummaryRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("courier_id", UUID.class),
                row.getObject("duty_session_id", UUID.class),
                row.getObject("shipment_id", UUID.class),
                row.getInt("distance_meters"),
                row.getInt("observation_count"),
                row.getObject("first_observed_at", OffsetDateTime.class).toInstant(),
                row.getObject("last_observed_at", OffsetDateTime.class).toInstant(),
                row.getString("protected_pickup_point"),
                row.getString("protected_delivery_point"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Double doubleOrNull(ResultSet row, String column) throws SQLException {
        java.math.BigDecimal value = row.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------- row types

    public record DutySessionRow(
            UUID id, UUID tenantId, UUID brandId, UUID locationId, UUID courierId, UUID shiftId,
            String deviceId, DutySessionStatus status, CollectionGate collectionGate,
            Instant registrationCheckedAt, LocalDate registrationValidUntil,
            String openedBySubject, Instant startedAt, Instant suspendedAt,
            Instant endedAt, String endReason, int version) {
    }

    public record LivePositionRow(
            UUID tenantId, UUID courierId, UUID dutySessionId, UUID brandId, UUID locationId,
            double latitude, double longitude, double accuracyMeters,
            Double headingDegrees, Double speedMps, Integer batteryPercent, Boolean deviceCharging,
            int activeAssignmentCount, Instant capturedAt, Instant receivedAt) {
    }

    /**
     * One courier's distance from a branch, with the two facts that decide
     * whether it may be believed.
     *
     * <p>Carries no coordinate on purpose. This row exists so that
     * {@code CourierProximityPort} can answer a metre count without anything
     * upstream of it ever holding a position, and adding a latitude here would
     * quietly undo that.
     */
    public record ProximityRow(UUID courierId, double metres, double accuracyMeters,
            Instant capturedAt) {
    }

    public record TrackWindowRow(
            UUID id, UUID tenantId, UUID courierId, UUID dutySessionId,
            Instant windowStart, Instant windowEnd, String geohash5First, String geohash5Last,
            int observationCount, int distanceMeters, String protectedTrack, Instant createdAt) {
    }

    public record TrackSummaryRow(
            UUID id, UUID tenantId, UUID courierId, UUID dutySessionId, UUID shipmentId,
            int distanceMeters, int observationCount, Instant firstObservedAt, Instant lastObservedAt,
            String protectedPickupPoint, String protectedDeliveryPoint, Instant createdAt) {
    }

    /** What a session's windows add up to, computed without decrypting one. */
    public record TrackAggregate(
            int distanceMeters, int observationCount, Instant firstObservedAt, Instant lastObservedAt) {
    }
}
