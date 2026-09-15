/**
 * What telemetry exposes to other modules (ADR 0045).
 *
 * <p>Two directions, and neither carries a coordinate.
 *
 * <p>Outward: {@link uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher} is
 * how ordering, catalog, fulfillment, and integration say that something in a
 * scope changed, and {@link uz.horecaos.platform.telemetry.api.StreamChannel} is the
 * catalogue of what may be said. A signal names a resource; it never carries it.
 *
 * <p>Inward: {@link uz.horecaos.platform.telemetry.api.CourierShiftPort} and
 * {@link uz.horecaos.platform.telemetry.api.SettlementCalendarPort} are the two
 * facts ADR 0042 owns and this module refuses to invent — the shift a duty
 * session opens from, and the settlement calendar the retention floor is derived
 * from.
 *
 * <p>Outward again, and the one that needed an argument:
 * {@link uz.horecaos.platform.telemetry.api.CourierProximityPort} answers how far a
 * courier is from a named branch, in metres. ADR 0042's dispatch ranking prefers
 * the nearer courier and would otherwise have to be handed a coordinate to work
 * it out. A distance from a branch the caller already named is a circle rather
 * than a pin, and it is the same reduction that already crosses this boundary
 * inside a {@code FleetCandidate}.
 *
 * <p>Inward once more, and unrelated to ADR 0042:
 * {@link uz.horecaos.platform.telemetry.api.SnapshotSource} is how a
 * {@code SNAPSHOT}-class channel gets its payload. {@code COUNTERS} carries an
 * aggregate {@code ordering} owns, so the source that answers it lives in
 * {@code ordering} and implements this published interface rather than
 * telemetry inventing a second query path onto data it does not own (wave
 * P08).
 *
 * <p>Nothing here exposes a position, a track, or a duty session row. Those are
 * read through capability-gated HTTP, which is what keeps every read of them
 * authorized at a location scope rather than at a module boundary.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.telemetry.api;
