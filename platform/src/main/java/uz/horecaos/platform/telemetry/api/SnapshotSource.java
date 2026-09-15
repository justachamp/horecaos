package uz.horecaos.platform.telemetry.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What a registered snapshot channel puts in its frame (ADR 0045).
 *
 * <p>Only two channels have one, and both had to argue for it. {@code COUNTERS}
 * carries its integers inline because a signal saying "a number changed" followed
 * by a fetch is two round trips for one integer. {@code COURIER_POSITIONS} carries
 * positions inline because a signal per courier per tick would produce N fetches
 * per tick for N couriers.
 *
 * <p>Everything else is signal-not-state, and that is the rule rather than the
 * exception: a snapshot duplicates a read model onto a second contract to
 * version, test, and classification-check separately, and it re-authorizes
 * nothing on its own — the registry checks the channel's capability before every
 * snapshot it sends, because the alternative is a stream that keeps emitting
 * after a grant is revoked.
 *
 * <p><strong>Published in {@code telemetry.api} rather than kept internal</strong>
 * (wave P08), because {@code COUNTERS} carries an aggregate {@code ordering}
 * owns and computes — {@code CourierPositionSnapshotSource} answers for itself
 * from inside this module, but nothing here can compute an order count without
 * either duplicating {@code ordering}'s query or importing its internals, both
 * of which ADR 0019's module boundary refuses. This is the same "inward port"
 * shape as {@link CourierShiftPort} and {@link SettlementCalendarPort}: telemetry
 * declares the contract, another module implements it, and {@link
 * uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry}
 * collects every implementation Spring finds regardless of which module it came
 * from — module boundaries are a static-analysis property of imports, not of
 * runtime wiring.
 */
public interface SnapshotSource {

    StreamChannel channel();

    /**
     * The payload for one scope, or empty when there is nothing to say.
     *
     * <p>Empty is not an error and is not a frame. A branch with no couriers on
     * duty should not receive an empty list every five seconds all shift.
     */
    Optional<Object> snapshot(UUID tenantId, ScopeKey scopeKey);
}
